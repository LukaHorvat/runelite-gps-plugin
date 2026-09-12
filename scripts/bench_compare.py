#!/usr/bin/env python3
"""Compares two JMH JSON result files, the checked-in baseline against a fresh run.

    ./gradlew -Pjmh jmh --args='-rf json -rff build/jmh/result.json'
    python scripts/bench_compare.py [--baseline docs/benchmarks/baseline.json]
                                    [--result build/jmh/result.json] [--threshold 15] [--markdown]

Every benchmark + parameter combination is matched by name; the delta is the result's score
relative to the baseline's. Deltas beyond the threshold (percent) are marked SLOWER or FASTER;
entries present on only one side are listed at the end. JMH scores are noisy by a few percent
between runs on the same machine and not comparable across machines, so treat single-digit
deltas as noise and re-run before believing a marked one.
"""
import argparse
import json
import sys

UNIT_SCALE = {'ns/op': 1e-3, 'us/op': 1.0, 'ms/op': 1e3, 's/op': 1e6}


def load(path):
    with open(path, encoding='utf-8') as f:
        entries = json.load(f)
    rows = {}
    for entry in entries:
        name = entry['benchmark']
        for prefix in ('gps.pathfinder.', 'gps.'):
            if name.startswith(prefix):
                name = name[len(prefix):]
                break
        params = entry.get('params') or {}
        key = (name, tuple(sorted(params.items())))
        rows[key] = (entry['primaryMetric']['score'], entry['primaryMetric']['scoreUnit'])
    return rows


def to_unit(score, unit, target):
    if unit == target:
        return score
    return score * UNIT_SCALE[unit] / UNIT_SCALE[target]


def fmt(score):
    if score >= 100:
        return '%.0f' % score
    if score >= 10:
        return '%.1f' % score
    return '%.2f' % score


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--baseline', default='docs/benchmarks/baseline.json')
    parser.add_argument('--result', default='build/jmh/result.json')
    parser.add_argument('--threshold', type=float, default=15.0, help='percent; marks deltas beyond it')
    parser.add_argument('--markdown', action='store_true', help='print a markdown table')
    parser.add_argument('--fail-on-slower', action='store_true', help='exit 1 when any entry is marked SLOWER')
    args = parser.parse_args()

    baseline = load(args.baseline)
    result = load(args.result)
    common = sorted(set(baseline) & set(result))
    lines = []
    slower = 0
    for key in common:
        base_score, unit = baseline[key]
        new_score = to_unit(result[key][0], result[key][1], unit)
        delta = (new_score - base_score) / base_score * 100 if base_score else 0.0
        mark = ''
        if delta > args.threshold:
            mark = 'SLOWER'
            slower += 1
        elif delta < -args.threshold:
            mark = 'FASTER'
        params = ' '.join('%s=%s' % kv for kv in key[1])
        lines.append((key[0], params, unit, fmt(base_score), fmt(new_score), '%+.1f%%' % delta, mark))

    if args.markdown:
        print('| Benchmark | Params | Unit | Baseline | Result | Delta | |')
        print('|---|---|---|---:|---:|---:|---|')
        for row in lines:
            print('| ' + ' | '.join(row) + ' |')
    else:
        widths = [max(len(row[i]) for row in lines + [('Benchmark', 'Params', 'Unit', 'Baseline', 'Result', 'Delta', '')])
                  for i in range(7)]
        header = ('Benchmark', 'Params', 'Unit', 'Baseline', 'Result', 'Delta', '')
        for row in [header] + lines:
            print('  '.join(cell.ljust(widths[i]) if i < 3 else cell.rjust(widths[i]) for i, cell in enumerate(row)).rstrip())

    only_base = sorted(set(baseline) - set(result))
    only_new = sorted(set(result) - set(baseline))
    for label, keys in (('only in baseline', only_base), ('only in result', only_new)):
        for key in keys:
            print('%s: %s %s' % (label, key[0], ' '.join('%s=%s' % kv for kv in key[1])))
    print('%d compared, %d slower, %d faster (threshold %.0f%%)' % (
        len(common), slower, sum(1 for row in lines if row[6] == 'FASTER'), args.threshold))
    if args.fail_on_slower and slower:
        sys.exit(1)


if __name__ == '__main__':
    main()
