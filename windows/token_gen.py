#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""七牛长期上传凭证生成 + 全链路自检。

用法（在项目根目录执行）:
  生成凭证（默认 10 年有效期，限定 打卡/ 前缀）:
    python token_gen.py --ak <AK> --sk <SK> --bucket <桶名> --region z0
  生成并保存到文件:
    python token_gen.py ... --out token.txt
  全链路自检（会临时上传一张测试图到桶中并删除）:
    python token_gen.py ... --verify

说明:
  - 凭证算法: policy JSON -> urlsafe_base64 -> HMAC-SHA1(SK, encoded) -> AK:sign:policy
  - policy 权限收敛: 仅能向 <prefix> 前缀新增 <=10MB 的图片（insertOnly，不可覆盖/删除/列举）
  - --verify 会用 requests 模拟手机 MacroDroid 的 multipart 表单上传（中文 key + 无前导零日期），
    验证通过即代表手机端配置成功概率极高。
"""
import argparse
import base64
import datetime
import hashlib
import hmac
import io
import json
import sys
import time

UP_HOSTS = {
    'z0': 'https://up-z0.qiniup.com',
    'cn-east-2': 'https://up-cn-east-2.qiniup.com',
    'z1': 'https://up-z1.qiniup.com',
    'z2': 'https://up-z2.qiniup.com',
    'na0': 'https://up-na0.qiniup.com',
    'as0': 'https://up-as0.qiniup.com',
}

FSIZE_LIMIT = 10 * 1024 * 1024  # 10MB


def urlsafe_b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode('ascii')


def make_policy(bucket: str, prefix: str, years: int) -> dict:
    return {
        'scope': '{0}:{1}'.format(bucket, prefix),
        'isPrefixalScope': 1,   # 必加，否则前缀限定不生效
        'deadline': int(time.time()) + years * 365 * 24 * 3600,
        'insertOnly': 1,        # 只允许新增，不允许覆盖
        'fsizeLimit': FSIZE_LIMIT,
        'mimeLimit': 'image/*',
    }


def make_token(ak: str, sk: str, policy: dict) -> str:
    encoded = urlsafe_b64(json.dumps(policy, separators=(',', ':'), ensure_ascii=False).encode('utf-8'))
    sign = hmac.new(sk.encode('utf-8'), encoded.encode('utf-8'), hashlib.sha1).digest()
    return '{0}:{1}:{2}'.format(ak, urlsafe_b64(sign), encoded)


def parse_token(token: str):
    """反解 token，返回 (ak, sign, encoded_policy, policy_dict)。"""
    parts = token.split(':')
    if len(parts) != 3:
        raise ValueError('token 格式错误（应为 AK:sign:policy 三段）')
    ak, sign, encoded = parts
    policy = json.loads(base64.urlsafe_b64decode(encoded).decode('utf-8'))
    return ak, sign, encoded, policy


def verify_sign_with_sdk(ak: str, sk: str, encoded: str, sign: str) -> bool:
    """用官方 SDK 交叉验证签名一致性。"""
    try:
        from qiniu import Auth
        sdk_token = Auth(ak, sk).token(encoded)  # 返回 "AK:sign"
        return sdk_token.split(':', 1)[1] == sign
    except Exception:
        return False


def make_test_image() -> bytes:
    from PIL import Image
    img = Image.new('RGB', (64, 64), (0, 120, 215))
    buf = io.BytesIO()
    img.save(buf, format='JPEG')
    return buf.getvalue()


def macrodroid_snippet(token: str, host: str, work: str) -> str:
    """生成给 MacroDroid Shell Script 动作粘贴的脚本片段。"""
    return f"""TOKEN="{token}"
HOST="{host}/"
WORK="{work}"   # 上班=上班打卡- / 下班=下班打卡-
B="qiniuboundary4clockin"
FN="/sdcard/Download/qiniu_tmp/payload.bin"
SRC="{{file_path}}"
D=$(date +%Y-%m-%d | sed 's/-0/-/g')
KEY="打卡/$(echo $D | cut -d- -f1)年$(echo $D | cut -d- -f2)月$(echo $D | cut -d- -f3)日/$WORK$(basename "$SRC")"
mkdir -p /sdcard/Download/qiniu_tmp
printf -- '--%s\\r\\nContent-Disposition: form-data; name="token"\\r\\n\\r\\n%s\\r\\n' "$B" "$TOKEN" > "$FN"
printf -- '--%s\\r\\nContent-Disposition: form-data; name="key"\\r\\n\\r\\n%s\\r\\n' "$B" "$KEY" >> "$FN"
printf -- '--%s\\r\\nContent-Disposition: form-data; name="file"; filename="%s"\\r\\nContent-Type: image/jpeg\\r\\n\\r\\n' "$B" "$(basename "$SRC")" >> "$FN"
cat "$SRC" >> "$FN"
printf -- '\\r\\n--%s--\\r\\n' "$B" >> "$FN"
"""


def cmd_generate(args):
    policy = make_policy(args.bucket, args.prefix, args.years)
    token = make_token(args.ak, args.sk, policy)
    ak, sign, encoded, decoded = parse_token(token)
    assert decoded == policy, '反解 policy 不一致'

    host = UP_HOSTS.get(args.region)
    if host is None:
        print('[FAIL] 未知区域: {0}，可选: {1}'.format(args.region, ', '.join(UP_HOSTS)))
        sys.exit(2)

    deadline_dt = datetime.datetime.fromtimestamp(policy['deadline'])
    print('=' * 64)
    print('上传凭证生成成功（有效期至 {0}，共 {1} 年）'.format(deadline_dt.strftime('%Y-%m-%d'), args.years))
    print('权限: 只能向 {0} 前缀新增 <=10MB 图片（不可覆盖/删除/列举）'.format(args.prefix))
    print('-' * 64)
    print('上传凭证(token):')
    print(token)
    print('-' * 64)
    print('上传端点(POST): {0}/'.format(host))
    if args.verify:
        run_verify(args, token, host)
    else:
        sdk_ok = verify_sign_with_sdk(args.ak, args.sk, encoded, sign)
        print('[{0}] SDK 签名交叉验证'.format('OK' if sdk_ok else 'SKIP/FAIL'))
        print()
        print('下一步: 1) 将 token 与端点按 docs/MacroDroid配置步骤.md 配置手机；')
        print('      2) 建议先执行 --verify 做全链路自检。')
    if args.out:
        with open(args.out, 'w', encoding='utf-8') as f:
            f.write('token: {0}\nhost: {1}/\n'.format(token, host))
            f.write(macrodroid_snippet(token, host, '上班打卡-'))
        print('已保存到: {0}'.format(args.out))
    return token


def run_verify(args, token: str, host: str):
    print('=' * 64)
    print('开始全链路自检...')
    ok_all = True

    # 1. 管理面连通（list）
    try:
        from qiniu import Auth, BucketManager
        bm = BucketManager(Auth(args.ak, args.sk))
        ret, eof, info = bm.list(args.bucket, prefix=args.prefix, limit=1)
        assert info.ok(), 'list 返回错误: {0}'.format(getattr(info, 'error', info))
        print('[OK] 1/4 管理面 list 连通（AK/SK/桶/区域正确）')
    except Exception as e:
        print('[FAIL] 1/4 管理面 list 连通: {0}'.format(e))
        print('       请检查 AK/SK、桶名、区域是否正确。')
        sys.exit(1)

    # 2. 模拟手机端 multipart 表单上传（中文 key + 无前导零日期）
    now = datetime.datetime.now()
    date_str = '{0}年{1}月{2}日'.format(now.year, now.month, now.day)
    key = '{0}{1}/上班打卡-_selftest.jpg'.format(args.prefix, date_str)
    img = make_test_image()
    try:
        import requests
        r = requests.post(
            host + '/',
            data={'token': token, 'key': key},
            files={'file': ('test.jpg', img, 'image/jpeg')},
            timeout=60,
        )
        body = {}
        try:
            body = r.json()
        except Exception:
            pass
        assert r.status_code == 200 and 'key' in body, 'HTTP {0}: {1}'.format(r.status_code, r.text[:200])
        print('[OK] 2/4 表单上传成功（中文 key: {0}）'.format(key))
    except AssertionError as e:
        print('[FAIL] 2/4 表单上传: {0}'.format(e))
        print('       常见原因: token policy 与桶不符 / 前缀权限 / 区域端点错误。')
        sys.exit(1)

    # 3. stat 比对文件大小（验证二进制无损）
    try:
        ret, info = bm.stat(args.bucket, key)
        assert info.ok(), 'stat 错误: {0}'.format(getattr(info, 'error', info))
        assert ret['fsize'] == len(img), 'fsize 不一致: 远端 {0} / 本地 {1}'.format(ret['fsize'], len(img))
        print('[OK] 3/4 stat 校验 fsize 一致（{0} 字节）'.format(len(img)))
    except Exception as e:
        print('[FAIL] 3/4 stat 校验: {0}'.format(e))
        ok_all = False

    # 4. 清理测试对象
    try:
        ret, info = bm.delete(args.bucket, key)
        assert info.ok(), 'delete 错误: {0}'.format(getattr(info, 'error', info))
        print('[OK] 4/4 测试对象已删除')
    except Exception as e:
        print('[FAIL] 4/4 清理测试对象: {0}（请手动到七牛控制台删除 {1}）'.format(e, key))
        ok_all = False

    print('=' * 64)
    if ok_all:
        print('全部通过！手机端按 docs/MacroDroid配置步骤.md 配置即可。')
        print()
        print('=== MacroDroid Shell Script 可直接粘贴（上班版）===')
        print(macrodroid_snippet(token, host, '上班打卡-'))
    else:
        print('存在失败项，请根据上方提示排查。')
        sys.exit(1)


def main():
    ap = argparse.ArgumentParser(description='七牛长期上传凭证生成 + 自检')
    ap.add_argument('--ak', required=True, help='七牛 Access Key')
    ap.add_argument('--sk', required=True, help='七牛 Secret Key')
    ap.add_argument('--bucket', required=True, help='七牛空间(bucket)名')
    ap.add_argument('--region', required=True, choices=list(UP_HOSTS), help='桶所在区域')
    ap.add_argument('--years', type=int, default=10, help='凭证有效年数（默认 10）')
    ap.add_argument('--prefix', default='打卡/', help='允许上传的 key 前缀（默认 打卡/）')
    ap.add_argument('--verify', action='store_true', help='生成后立即做全链路自检（上传测试图并删除）')
    ap.add_argument('--out', help='将 token 与配置片段保存到该文件')
    args = ap.parse_args()
    cmd_generate(args)


if __name__ == '__main__':
    main()
