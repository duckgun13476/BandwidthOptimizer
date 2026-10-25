"""Checks the complete UI vocabulary against phases, finite states and formatting contracts."""
from pathlib import Path
import json,re
root=Path(__file__).resolve().parents[2]
langs=[json.loads((root/'common/src/main/resources/assets/bandwidthoptimizer/lang'/f'{lang}.json').read_text(encoding='utf-8-sig')) for lang in ('en_us','zh_cn','pt_br')]
sets=[{k for k in d if k.startswith('bo.network.')} for d in langs]
assert sets[0]==sets[1]==sets[2]
for key in sets[0]:
 assert all(isinstance(d[key],str) and d[key].strip() for d in langs),key
 assert len({tuple(re.findall(r'%(?:\d+\$)?[sdif]',d[key])) for d in langs})==1,key
pkg=root/'common/src/main/java/com/PinkCats/bandwidthoptimizer/networkprobe'
session=(pkg/'NetworkProbeSession.java').read_text(encoding='utf-8')
phases=re.search(r'enum Phase\s*\{([^}]+)',session).group(1)
for phase in re.findall(r'\b[A-Z_]+\b',phases):assert 'bo.network.phase.'+phase in sets[0],phase
screen=(pkg/'NetworkAnalysisScreenBase.java').read_text(encoding='utf-8')
for key in re.findall(r'"(bo\.network\.[a-zA-Z_][a-zA-Z_.]+)"',screen):
 if not key.endswith('.'):assert key in sets[0],key
states=re.search(r'case (.*?) -> state;',screen,re.S).group(1)
for state in re.findall(r'"([a-z_]+)"',states):assert 'bo.network.state.'+state in sets[0],state
print(f'NETWORK_ANALYSIS_LANGUAGES_PASS languages=3 keys={len(sets[0])} all_session_phases=true all_screen_states=true placeholder_contract=true')
