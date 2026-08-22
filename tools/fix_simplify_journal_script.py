from pathlib import Path

path = Path("tools/simplify_journal_time.py")
text = path.read_text()
old = '''    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)
'''
new = '''    count = text.count(old)
    if count < 1:
        raise SystemExit(f"{label}: expected at least 1 match, got {count}")
    return text.replace(old, new, 1)
'''
if text.count(old) != 1:
    raise SystemExit("bootstrap helper signature changed")
path.write_text(text.replace(old, new, 1))
