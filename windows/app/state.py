# -*- coding: utf-8 -*-
"""同步状态（%APPDATA%\\qiniu-clockin-sync\\sync_state.json）。

结构: {key: {"etag":..., "fsize":..., "local_path":..., "synced_at":...}}
"""
import json
import os
import time

from app.config import APP_DIR

STATE_PATH = os.path.join(APP_DIR, 'sync_state.json')


def load() -> dict:
    try:
        with open(STATE_PATH, 'r', encoding='utf-8') as f:
            return json.load(f)
    except Exception:
        return {}


def save(state: dict) -> None:
    os.makedirs(APP_DIR, exist_ok=True)
    tmp = STATE_PATH + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(state, f, ensure_ascii=False)
    os.replace(tmp, STATE_PATH)


def record(state: dict, key, etag, fsize, local_path) -> None:
    state[key] = {
        'etag': etag,
        'fsize': fsize,
        'local_path': local_path,
        'synced_at': int(time.time()),
    }
