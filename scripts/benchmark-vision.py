#!/usr/bin/env python3
"""Replay an exported phone vision sample against Ollama. Python stdlib only."""
import argparse
import copy
import json
import math
import platform
import statistics
import time
import urllib.error
import urllib.request
from pathlib import Path


def call(base, path, payload=None):
    request = urllib.request.Request(base.rstrip('/') + path,
        data=json.dumps(payload).encode() if payload is not None else None,
        headers={'Content-Type': 'application/json'})
    # Local benchmarks must not accidentally go through an environment proxy.
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=300) as response:
        return json.load(response)


def seconds(data, field):
    value = data.get(field)
    return round(value / 1e9, 4) if isinstance(value, (int, float)) else None


def summarize(values, threshold):
    ordered = sorted(values)
    return {'mean_seconds': round(statistics.mean(values), 3),
        'median_seconds': round(statistics.median(values), 3),
        'p95_seconds': round(ordered[math.ceil(len(ordered) * .95)-1], 3),
        'min_seconds': round(min(values), 3), 'max_seconds': round(max(values), 3),
        'under_threshold': sum(v < threshold for v in values), 'runs': len(values)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sample', type=Path, help='Exported latest.json')
    parser.add_argument('--url', default='http://127.0.0.1:11434')
    parser.add_argument('--runs', type=int, default=5)
    parser.add_argument('--warmup', type=int, default=1)
    parser.add_argument('--reload-model', action='store_true', help='Unload before every request to avoid cross-request caches; includes model loading')
    parser.add_argument('--model', help='Override model (different workload, noted in report)')
    parser.add_argument('--threads', type=int, help='Override the original CPU thread setting')
    parser.add_argument('--threshold', type=float, default=3)
    parser.add_argument('--output', type=Path, default=Path('vision-benchmark-report.json'))
    args = parser.parse_args()
    if args.runs < 1 or args.warmup < 0 or args.threshold <= 0 or (args.threads is not None and args.threads < 1):
        parser.error('runs/threads/threshold must be positive; warmup must be nonnegative')
    sample = json.loads(args.sample.read_text(encoding='utf-8'))
    if sample.get('schema_version') != 1:
        parser.error('Unsupported sample schema')
    payload = copy.deepcopy(sample['request'])
    if args.model:
        payload['model'] = args.model
    if args.threads:
        payload['options']['num_thread'] = args.threads
    payload['stream'] = False
    report = {'client_platform': platform.platform(), 'server_url': args.url,
        'target': sample['target'], 'model': payload['model'], 'options': payload['options'],
        'baseline': sample['baseline'], 'threshold_seconds': args.threshold,
        'scope': 'HTTP model request only; excludes ADB capture and image preprocessing',
        'reload_before_each_request': args.reload_model,
        'warmup': [], 'runs': []}
    # Do not duplicate the screenshot in performance reports.
    print('模型:', payload['model'], '目标:', sample['target'], flush=True)
    print('只测模型请求；不包含手机截图和图片预处理。预热请求单独记录。', flush=True)
    for phase, count in [('warmup', args.warmup), ('runs', args.runs)]:
        for index in range(count):
            if args.reload_model:
                call(args.url, '/api/generate', {'model': payload['model'], 'keep_alive': 0})
            started = time.perf_counter()
            data = call(args.url, '/api/chat', payload)
            elapsed = time.perf_counter()-started
            if data.get('error') or data.get('done') is not True:
                raise ValueError(f'Incomplete model response: {data.get("error", data)}')
            content = data.get('message', {}).get('content', '')
            try:
                parsed = json.loads(content)
                box = parsed.get('box')
                valid = type(parsed.get('found')) is bool and (
                    not parsed['found'] or isinstance(box, list) and len(box) == 4
                    and all(type(v) in (int, float) and math.isfinite(v) and 0 <= v <= 1000 for v in box)
                    and box[0] < box[2] and box[1] < box[3])
            except (ValueError, TypeError, AttributeError):
                parsed, valid = None, False
            record = {'wall_seconds': round(elapsed, 4), 'valid_result': valid,
                'result': parsed, 'response': data,
                **{field + '_seconds': seconds(data, field) for field in
                   ('total_duration', 'load_duration', 'prompt_eval_duration', 'eval_duration')}}
            report[phase].append(record)
            print(f'{phase} {index+1}/{count}: {elapsed:.2f}s valid={valid} result={content}', flush=True)
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    report['summary'] = summarize([r['wall_seconds'] for r in report['runs']], args.threshold)
    report['summary']['valid_results'] = sum(r['valid_result'] for r in report['runs'])
    for name, path in [('ollama_version', '/api/version'), ('loaded_models', '/api/ps'), ('models', '/api/tags')]:
        try:
            report[name] = call(args.url, path)
        except (OSError, ValueError) as error:
            report[name] = {'error': str(error)}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report['summary'], ensure_ascii=False, indent=2))
    print('报告:', args.output.resolve())
    print('valid 仅表示结果格式正确；请对照原图和基准结果核对定位准确率。')
    print('重复同一图片可能命中缓存；可加 --reload-model 测试无跨请求缓存、包含模型加载的耗时。')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, urllib.error.URLError) as error:
        raise SystemExit(f'测试失败：{error}')
