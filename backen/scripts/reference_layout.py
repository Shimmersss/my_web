import re


REFERENCE_NUMBER_RE = re.compile(
    r"^\s*(?:\[(\d{1,4})\]|\((\d{1,4})\)|(\d{1,4})[.)])\s*"
)
REFERENCE_YEAR_RE = re.compile(r"\b(?:18|19|20)\d{2}[a-z]?\b", re.IGNORECASE)
REFERENCE_SIGNAL_RE = re.compile(
    r"\b(?:doi|https?://|et\s+al\.?|vol\.?|no\.?|pp?\.?|journal|proceedings|conference)\b",
    re.IGNORECASE,
)


def reference_number(text):
    match = REFERENCE_NUMBER_RE.match(text or "")
    if not match:
        return None
    return int(next(value for value in match.groups() if value is not None))


def is_numbered_reference(text):
    """Keep numbered lists untouched unless their content looks bibliographic."""
    if reference_number(text) is None:
        return False
    return bool(REFERENCE_YEAR_RE.search(text) or REFERENCE_SIGNAL_RE.search(text))


def reference_split_points(lines):
    numbered_lines = [
        (index, reference_number(text))
        for index, text in enumerate(lines)
        if reference_number(text) is not None
    ]
    numbers = [number for _, number in numbered_lines]
    has_sequence = any(
        current == previous + 1 for previous, current in zip(numbers, numbers[1:])
    )
    has_bibliographic_signal = any(is_numbered_reference(text) for text in lines)
    if len(numbered_lines) < 2 or not has_sequence or not has_bibliographic_signal:
        return []
    return [index for index, _ in numbered_lines]
