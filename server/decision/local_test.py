"""Interactive CPU BGE diagnostics; no API server, LLM or tool execution."""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from time import perf_counter

SERVER = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER))


def probability_range(value: str) -> float:
    number = float(value)
    if not 0 <= number <= 1:
        raise argparse.ArgumentTypeError("请输入 0 到 1 之间的数值")
    return number


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="本地 CPU BGE 工具路由测试（不会执行工具）")
    local_model = SERVER / "models" / "bge-small-zh-v1.5"
    parser.add_argument("--model", default=str(local_model) if (local_model / "config.json").exists()
                        else "BAAI/bge-small-zh-v1.5", help="Hugging Face 模型名或本地模型目录")
    parser.add_argument("--offline", action="store_true", help="只用本地文件或缓存，不下载")
    parser.add_argument("--text", action="append", help="测试一句话；可以重复传入")
    parser.add_argument("--cases", nargs="?", const=str(Path(__file__).with_name("cases.json")),
                        help="批量测试 JSON 语料；不传路径则使用自带语料")
    parser.add_argument("--threshold", type=probability_range, default=.82)
    parser.add_argument("--margin", type=probability_range, default=.08)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args(argv)
    if args.threads < 1:
        parser.error("--threads 必须大于 0")
    return args


def inspect_text(router, text: str) -> dict:
    from app.decision import DecisionRequest, REGISTRY

    request = DecisionRequest(text=text, available_tools=[tool.name for tool in REGISTRY])
    started = perf_counter()
    decision = router.decide(request)
    route_ms = (perf_counter() - started) * 1000

    # Diagnostic scores deliberately bypass the policy gate. A question can have a
    # high raw score and still correctly fall back. Scores never execute anything.
    started = perf_counter()
    vector = router.encode([text])[0]
    embedding_ms = (perf_counter() - started) * 1000
    scores = {}
    for (name, _), embedding in zip(router.samples, router.vectors):
        score = sum(float(a) * float(b) for a, b in zip(vector, embedding))
        scores[name] = max(score, scores.get(name, -1.))
    ranked = sorted(scores.items(), key=lambda item: item[1], reverse=True)
    return {
        "text": text,
        "decision": decision.model_dump(),
        "top_candidates": [{"tool": name, "score": round(score, 4)} for name, score in ranked[:3]],
        "top_margin": round(ranked[0][1] - ranked[1][1], 4),
        "route_ms": round(route_ms, 2),
        "diagnostic_embedding_ms": round(embedding_ms, 2),
    }


def batch(router, cases: list[dict]) -> int:
    failed = 0
    for case in cases:
        result = inspect_text(router, case["text"])
        decision = result["decision"]
        ok = decision["type"] == case["type"] and decision.get("tool") == case.get("tool")
        failed += not ok
        result.update({"pass": ok, "expected": {"type": case["type"], "tool": case.get("tool")}})
        print(json.dumps(result, ensure_ascii=False))
    print(f"通过 {len(cases) - failed}/{len(cases)}；失败 {failed}")
    return int(failed > 0)


def main(argv=None) -> int:
    args = parse_args(argv)
    # Keep model downloads in the project's ignored model directory.
    os.environ.setdefault("HF_HOME", str(SERVER / "models" / "hf-cache"))
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    try:
        import torch
        from sentence_transformers import SentenceTransformer
        from app.decision import Router
    except ImportError as exc:
        print(f"缺少依赖：{exc}。请先按 docs/decision-engine.md 安装本地测试依赖。", file=sys.stderr)
        return 2
    torch.set_num_threads(args.threads)
    print(f"加载模型：{args.model}（CPU）；首次联网运行需要下载权重。", flush=True)
    started = perf_counter()
    try:
        model = SentenceTransformer(args.model, device="cpu", local_files_only=args.offline,
                                    trust_remote_code=False)
        model.max_seq_length = 512

        def encode(texts):
            return model.encode(texts, normalize_embeddings=True, show_progress_bar=False, batch_size=16)

        router = Router(encode, threshold=args.threshold, margin=args.margin)
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"模型加载失败：{exc}\n检查网络、模型目录或 --offline 设置。", file=sys.stderr)
        return 2
    print(f"模型及工具向量已就绪，耗时 {perf_counter() - started:.2f}s；"
          f"阈值={args.threshold}，候选差距={args.margin}。")
    print("候选分数是原始 BGE 相似度，不是概率；最终决策还受规则限制。不会提取参数或执行工具。")
    if args.cases:
        cases = json.loads(Path(args.cases).read_text(encoding="utf-8"))
        return batch(router, cases)
    if args.text:
        for text in args.text:
            print(json.dumps(inspect_text(router, text), ensure_ascii=False, indent=2))
        return 0
    print("输入一句话测试；输入 /quit 退出。")
    while True:
        try:
            text = input("\n你> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return 0
        if text.lower() in {"/quit", "/exit"}:
            return 0
        if text:
            try:
                print(json.dumps(inspect_text(router, text), ensure_ascii=False, indent=2))
            except ValueError as exc:
                print(f"输入无效：{exc}")


if __name__ == "__main__":
    raise SystemExit(main())
