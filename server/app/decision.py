"""Shared routing policy. Embeddings suggest candidates, never execution permission."""
from __future__ import annotations
import json
import re
from pathlib import Path
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field

class ToolRegistration(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str
    description: str
    parameter_policy: Literal["NONE", "OPTIONAL", "REQUIRED"]
    examples: list[str] = Field(min_length=1)
    direct_phrases: list[str]
    target: Literal["android", "server"]

REGISTRY = [ToolRegistration.model_validate(item) for item in json.loads(
    Path(__file__).with_name("decision_tools.json").read_text(encoding="utf-8"))]
TOOLS_BY_NAME = {tool.name: tool for tool in REGISTRY}
if len(TOOLS_BY_NAME) != len(REGISTRY):
    raise ValueError("Duplicate decision tool registration")

class DecisionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str = Field(min_length=1, max_length=2000)
    available_tools: list[str] = Field(default_factory=list, max_length=30)

class Decision(BaseModel):
    model_config = ConfigDict(extra="forbid")
    type: Literal["DIRECT_TOOL", "TOOL_WITH_LLM", "LLM_FALLBACK"]
    tool: str | None = None
    score: float = Field(default=0, ge=-1, le=1)
    reason: str


def normalize(text: str) -> str:
    return re.sub(r"[\s，,。.!！]+", "", text).lower()


def informational(text: str) -> bool:
    return bool(re.search(
        r"[?？吗么呢]|什么|啥|为什么|怎么|如何|是否|能否|能不能|可不可以|可以|支持|"
        r"会不会|有没有|是不是|怎样|为何|咋|哪里|介绍|解释|请问|假如|如果|假设", text))


def operation_intent(text: str) -> bool:
    # A relevant topic (e.g. "my printer is broken") is not an operation request.
    # The lexical gate intentionally sacrifices recall; the original LLM handles variants.
    return bool(re.search(
        r"提醒我|提醒一下我|^(?:请|麻烦|帮我|给我|我要|我想|我需要)*"
        r"(?:打开|查看|看看|看一下|同步|打印(?!机)|设置提醒|记一下|记录一下|记一笔|记录一笔|记日记|写日记)", text.strip()))


def record_intent(text: str) -> bool:
    return bool(re.search(r"^(?:请|麻烦|帮我|给我|我要|我想|我需要)*(?:记一下|记录一下|记一笔|记录一笔|记日记|写日记)", text.strip())) and not non_action(text)


def non_action(text: str) -> bool:
    # Ambiguous, quoted, negative and multi-action turns retain the LLM context.
    return not operation_intent(text) or informational(text) or bool(re.search(
        r"不想|不需要|不要|不用|别|取消|停止|[‘’“”\"`]|然后|并且|同时|以及|再帮|顺便|先.+再|\n", text))


def fallback(reason: str) -> Decision:
    return Decision(type="LLM_FALLBACK", reason=reason)


def apply_policy(request: DecisionRequest, name: str, score: float) -> Decision:
    tool = TOOLS_BY_NAME.get(name)
    if non_action(request.text) or not tool or name not in request.available_tools:
        return fallback("non_action_or_unavailable")
    exact = normalize(request.text) in {normalize(p) for p in tool.direct_phrases}
    # Exact whole utterances only: extra words may be optional parameter values.
    # NONE also falls back for non-exact matches to avoid discarding qualifiers.
    if tool.parameter_policy == "NONE" and not exact:
        return fallback("qualified_parameterless_command")
    kind = "DIRECT_TOOL" if exact and tool.parameter_policy != "REQUIRED" else "TOOL_WITH_LLM"
    return Decision(type=kind, tool=name, score=score, reason="exact_command" if exact else "extract_parameters")


class Router:
    def __init__(self, encode, threshold: float = .82, margin: float = .08):
        self.encode = encode
        self.threshold, self.margin = threshold, margin
        self.samples = [(tool.name, sample) for tool in REGISTRY for sample in [tool.description, *tool.examples]]
        self.vectors = encode([s for _, s in self.samples])

    def decide(self, request: DecisionRequest) -> Decision:
        if non_action(request.text):
            return fallback("non_action")
        # Whole-command aliases are deterministic; semantic retrieval handles variants.
        exact = [t for t in REGISTRY if normalize(request.text) in {normalize(p) for p in t.direct_phrases}]
        if len(exact) == 1:
            return apply_policy(request, exact[0].name, 1)
        vector = self.encode([request.text])[0]
        scores = {}
        for (name, _), embedding in zip(self.samples, self.vectors):
            score = sum(float(a) * float(b) for a, b in zip(vector, embedding))
            scores[name] = max(score, scores.get(name, -1))
        ranked = sorted(scores.items(), key=lambda item: item[1], reverse=True)
        name, score = ranked[0]
        if score < self.threshold or score - ranked[1][1] < self.margin:
            return fallback("low_score_or_ambiguous")
        return apply_policy(request, name, min(1., max(-1., score)))
