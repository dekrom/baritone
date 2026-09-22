#!/usr/bin/env python3
"""SQLite regression model of the actual row cap and incremental exclusion."""
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[3]
source = (root / 'xaeroplus/common/src/main/java/xaeroplus/feature/highlights/ChunkHighlightDatabase.java').read_text()
cap = int(re.search(r'MAX_ROWS_PER_LOAD = ([\d_]+)', source).group(1).replace('_', ''))
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE highlights (x INTEGER, z INTEGER, foundTime INTEGER, PRIMARY KEY (x, z)) WITHOUT ROWID')
db.executemany('INSERT INTO highlights VALUES (?, ?, 1)', ((i // 2000 - 1000, i % 2000 - 1000) for i in range(cap + 1)))
# Exactly the existing region-bound arithmetic, for a 32-region-radius window.
previous = (-1024, 1024, -1024, 1024)
query = 'SELECT x,z FROM highlights WHERE x BETWEEN ? AND ? AND z BETWEEN ? AND ? LIMIT ?'
loaded = db.execute('SELECT count(*) FROM (' + query + ')', (*previous, cap)).fetchone()[0]
omitted = db.execute('SELECT x,z FROM highlights ORDER BY x,z LIMIT 1 OFFSET ?', (cap,)).fetchone()
assert loaded == cap
assert omitted is not None
assert db.execute(query, (*previous, cap)).fetchone() != omitted
# Shrink the view around the row the cap omitted; new rectangle is entirely inside old.
cx, cz = omitted[0] >> 5, omitted[1] >> 5
current = ((cx - 1) << 5, (cx + 1) << 5, (cz - 1) << 5, (cz + 1) << 5)
incremental_query = 'SELECT x,z FROM highlights WHERE x BETWEEN ? AND ? AND z BETWEEN ? AND ? AND NOT (x BETWEEN ? AND ? AND z BETWEEN ? AND ?) LIMIT ?'
incremental = db.execute(incremental_query, (*current, *previous, cap)).fetchall()
full = db.execute(query, (*current, cap)).fetchall()
assert omitted in full
assert omitted not in incremental
assert not incremental
print(f'CONFIRMED: loaded {loaded:,} of {cap + 1:,} rows; omitted {omitted}; zoom-in fetched {len(incremental)} rows, while a complete query finds {len(full)} including the omitted row.')
