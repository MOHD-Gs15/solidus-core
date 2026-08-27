#!/usr/bin/env python3
"""
Strip non-ASCII characters from Java source files.
Replaces common Unicode punctuation/symbols with ASCII equivalents.
"""
import os
import sys

REPLACEMENTS = {
    '\u2014': '-', '\u2013': '-', '\u2012': '-', '\u2010': '-', '\u2011': '-',
    '\u2018': "'", '\u2019': "'", '\u201A': "'", '\u201B': "'",
    '\u201C': '"', '\u201D': '"', '\u201E': '"', '\u201F': '"',
    '\u2192': '->', '\u2190': '<-', '\u2191': '^', '\u2193': 'v', '\u2194': '<->',
    '\u21D2': '=>', '\u21D0': '<=',
    '\u00D7': 'x', '\u00F7': '/',
    '\u2260': '!=', '\u2264': '<=', '\u2265': '>=',
    '\u221E': 'infinity', '\u2211': 'sum', '\u220F': 'product',
    '\u2208': 'in', '\u2209': 'not in', '\u2200': 'forall', '\u2203': 'exists',
    '\u2022': '*', '\u2023': '*', '\u25E6': 'o', '\u2043': '-',
    '\u204C': '*', '\u204D': '*',
    '\u00B7': '|', '\u2027': '|', '\u2219': '*', '\u22C5': '*',
    '\u00AB': '<<', '\u00BB': '>>', '\u2039': '<', '\u203A': '>',
    '\u2500': '-', '\u2501': '=', '\u2502': '|', '\u2503': '|',
    '\u250C': '+', '\u2510': '+', '\u2514': '+', '\u2518': '+',
    '\u251C': '+', '\u2524': '+', '\u252C': '+', '\u2534': '+', '\u253C': '+',
    '\u2550': '=', '\u2551': '|',
    '\u2554': '+', '\u2557': '+', '\u255A': '+', '\u255D': '+',
    '\u2560': '+', '\u2563': '+', '\u2566': '+', '\u2569': '+', '\u256C': '+',
    '\u2580': '#', '\u2581': '_', '\u2582': '_', '\u2583': '_',
    '\u2584': '_', '\u2585': '_', '\u2586': '_', '\u2587': '_',
    '\u2588': '#', '\u2589': '#', '\u258A': '#', '\u258B': '#',
    '\u258C': '#', '\u258D': '#', '\u258E': '#', '\u258F': '#',
    '\u2593': '#', '\u2592': ':', '\u2591': '.',
    '\u25B6': '>', '\u25C0': '<', '\u25B2': '^', '\u25BC': 'v',
    '\u25B7': '>', '\u25C1': '<', '\u25B3': '^', '\u25BD': 'v',
    '\u2605': '*', '\u2606': '*',
    '\u2728': '*', '\u2705': '[OK]', '\u2713': 'v', '\u2714': 'V',
    '\u2715': 'x', '\u2716': 'x', '\u2717': 'x', '\u2718': 'x',
    '\u2719': '*', '\u271A': '+', '\u271B': '+', '\u271C': '+',
    '\u00A0': ' ', '\u00A7': 'S',
    '\u00A9': '(c)', '\u00AE': '(r)',
    '\u00B0': ' deg ', '\u00B1': '+/-', '\u00B5': 'u', '\u2122': '(tm)',
    '\u26A0': '!', '\u26A1': '!', '\u2757': '!', '\u2764': '<3', '\u270D': '',
    '\uFE63': '-', '\uFF0D': '-', '\u2044': '/',
    '\uFEFF': '', '\u200B': '', '\u200C': '', '\u200D': '',
    '\u2028': '\n', '\u2029': '\n',
    '\u202F': ' ', '\u205F': ' ',
    '\u2003': ' ', '\u2002': ' ', '\u2004': ' ', '\u2005': ' ',
    '\u2006': ' ', '\u2007': ' ', '\u2008': ' ', '\u2009': ' ', '\u200A': ' ',
    '\u3000': ' ',
}

def clean_text(text):
    for unicode_char, ascii_replacement in REPLACEMENTS.items():
        text = text.replace(unicode_char, ascii_replacement)
    text = text.encode('ascii', errors='ignore').decode('ascii')
    return text

def process_file(filepath):
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            original = f.read()
    except UnicodeDecodeError:
        with open(filepath, 'r', encoding='latin-1') as f:
            original = f.read()
    cleaned = clean_text(original)
    if cleaned != original:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(cleaned)
        return True
    return False

def main():
    roots = sys.argv[1:] or ['src/main/java', 'src/test/java']
    changed = 0
    total = 0
    for root in roots:
        if not os.path.isdir(root):
            print(f"[skip] not a directory: {root}")
            continue
        for dirpath, _, filenames in os.walk(root):
            for fn in filenames:
                if not fn.endswith('.java'):
                    continue
                total += 1
                path = os.path.join(dirpath, fn)
                if process_file(path):
                    changed += 1
                    print(f"[ok]   cleaned: {path}")
    print(f"\nDone. Cleaned {changed} of {total} Java files.")

if __name__ == '__main__':
    main()
