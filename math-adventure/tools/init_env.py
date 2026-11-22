"""Create credentials once, without printing secrets or overwriting an existing file."""
import os
from pathlib import Path
import secrets

path = Path(__file__).resolve().parent.parent / '.env'
content = ('MATH_ADMIN_TOKEN='+secrets.token_urlsafe(32)+'\n'
           'MATH_BIND_ADDRESS=127.0.0.1\nMATH_PORT=8788\n'
           'MATH_TIMEZONE=Asia/Shanghai\nMATH_ROOT_PATH=\n')
try:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    raise SystemExit('已有.env，保留现有配置，不生成新令牌。')
with os.fdopen(fd, 'w') as stream:
    stream.write(content)
print('已创建math-adventure/.env；使用服务器编辑器查看家长令牌。')
