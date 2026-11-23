from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator

class Model(BaseModel):
    model_config = ConfigDict(extra='forbid', str_strip_whitespace=True)

class QuestionInput(Model):
    title: str = Field(min_length=1, max_length=40)
    content: str = Field(min_length=1, max_length=300)
    options: list[str] = Field(min_length=2, max_length=3)
    answer: int = Field(ge=0, le=2)
    hints: list[str] = Field(min_length=1, max_length=3)
    explanation: str = Field(min_length=1, max_length=400)
    topic: str = Field(min_length=1, max_length=40)
    skill_tags: list[str] = Field(min_length=1, max_length=5)
    difficulty: int = Field(default=1, ge=1, le=3)
    source_type: Literal['ai_draft', 'parent', 'licensed']
    source_note: str = Field(min_length=1, max_length=300)
    # Optional independently verifiable numeric calculation, never eval'd.
    expression: str | None = Field(default=None, max_length=100)

    @model_validator(mode='after')
    def structure(self):
        if self.answer >= len(self.options):
            raise ValueError('答案必须指向一个选项')
        if any(not s or len(s) > 60 for s in self.options) or len(set(self.options)) != len(self.options):
            raise ValueError('选项必须唯一且为1～60字')
        if any(not s or len(s) > 180 for s in self.hints):
            raise ValueError('提示必须为1～180字')
        if any(not s or len(s) > 40 for s in self.skill_tags):
            raise ValueError('能力标签必须为1～40字')
        return self

class Review(Model):
    reviewer: str = Field(default='家长', min_length=1, max_length=40)
    comment: str = Field(default='', max_length=500)
    independently_solved: bool

class Note(Model):
    comment: str = Field(min_length=1, max_length=500)

class DeviceInput(Model):
    name: str = Field(min_length=1, max_length=60)
    mode: Literal['practice', 'live'] = 'practice'

class PlayerInput(Model):
    nickname: str = Field(min_length=1, max_length=30)

class Operation(Model):
    request_id: str = Field(min_length=8, max_length=80, pattern=r'^[a-zA-Z0-9_-]+$')
    task_id: str = Field(min_length=1, max_length=64)

class Answer(Operation):
    answer: int = Field(ge=0, le=2)
    duration_seconds: int = Field(default=0, ge=0, le=86400)

class QuestionBatch(Model):
    questions: list[QuestionInput] = Field(min_length=1, max_length=50)
