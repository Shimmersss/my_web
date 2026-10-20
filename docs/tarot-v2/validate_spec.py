"""Read-only validation of the production specification, not an art quality test."""
import hashlib
import json
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
spec = json.loads((HERE / 'production-prompts.json').read_text())
source = json.loads((ROOT / 'backen/src/main/resources/tarot-major-v1.json').read_text())
assert [c['id'] for c in spec['cards']] == [c['id'] for c in source['cards']]
titles, existing, themes = set(), 0, 0
for card, original in zip(spec['cards'], source['cards']):
    assert card['name'] == original['name']
    path = ROOT / card['original']
    assert hashlib.sha256(path.read_bytes()).hexdigest() == card['originalSha256']
    assert len(card['themes']) == 4
    assert [t['suggestedSlot'] for t in card['themes']] == [2, 3, 4, 5]
    for theme in card['themes']:
        assert theme['title'] not in titles
        titles.add(theme['title'])
        assert len(theme['masterPrompt']) > 300
        assert set(theme['layerPrompts']) == {'subject', 'background', 'effects', 'text', 'lineart'}
        assert '禁止独立生图' in theme['layerPrompts']['lineart']
        assert theme['layerInputRole'] == 'actual-selected-master-path-required'
        if theme['existingMaster']:
            assert (ROOT / theme['existingMaster']).is_file()
            existing += 1
        else:
            assert theme['status'] == 'planned'
        themes += 1
for path in HERE.glob('*.md'):
    for target in re.findall(r'\]\(([^)]+)\)', path.read_text()):
        if '://' not in target and not target.startswith('#'):
            assert (path.parent / target.split('#')[0]).exists(), (path, target)
assert themes == 88 and existing == 4
json.loads((HERE / 'manifest-template.json').read_text())
print('PASS: 22 IDs/names/source hashes; 88 unique themes; 352 layer prompts; 88 lineart recipes; 4 existing masters; local links and JSON.')
print('This validates documentation only; it does not certify generated assets or theme quality.')

# v2.2: ensure the expanded handoff covers exactly the other 21 cards.
expanded = [c for c in spec['cards'] if c['id'] != 'major-18']
assert len(expanded) == 21
assert len(list((HERE / 'cards').glob('major-*.md'))) == 21
for card in expanded:
    guide = ROOT / card['productionGuide']
    body = guide.read_text()
    assert body.count('\n## 槽位') == 5
    assert card['baseSlot']['status'] == 'planned'
    assert card['baseSlot']['action'] == 'reuse-original-and-build-layered-holo-without-redesign'
    for theme in card['themes']:
        detail = theme['productionDetail']
        for key in ['composition', 'supportGeometry', 'connectedGroup', 'foilMask', 'specificQA']:
            assert detail[key] and detail[key] in body, (card['id'], theme['themeId'], key)
        assert detail['composition'] in theme['masterPrompt']
        assert detail['supportGeometry'] in theme['layerPrompts']['subject']
        assert detail['supportGeometry'] in theme['layerPrompts']['background']
        assert json.dumps(theme['startingParameters'], ensure_ascii=False) in body
        assert theme['status'] == 'planned'
for path in (HERE / 'cards').glob('*.md'):
    for target in re.findall(r'\]\(([^)]+)\)', path.read_text()):
        if '://' not in target and not target.startswith('#'):
            assert (path.parent / target.split('#')[0]).exists(), (path, target)
assert len(spec['referenceProjects']) == 5
for project in spec['referenceProjects']:
    assert (ROOT / project / 'README.md').exists()
print('PASS: 21 guides / 105 planned slots / 84 detailed themes; document-JSON compositions, supports, QA and parameters agree; five sample paths exist.')
