# -*- coding: utf-8 -*-
"""无控制台启动入口：pythonw 运行本文件即可常驻托盘。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app.main import main

if __name__ == '__main__':
    main()
