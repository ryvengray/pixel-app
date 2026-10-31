"""Minimal pixel-app adapter. Call display_text from your assistant tool handler."""
import json, os, urllib.request

def display_text(device_id: str, title: str, body: str) -> dict:
    base = os.environ['PIXEL_API_BASE'].rstrip('/')
    token = os.environ['PIXEL_TOKEN']
    if not device_id or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-' for c in device_id):
        raise ValueError('invalid device ID')
    request = urllib.request.Request(f'{base}/v1/devices/{device_id}/display',
        data=json.dumps({'title':title,'body':body}, ensure_ascii=False).encode(),
        headers={'Content-Type':'application/json','Authorization':f'Bearer {token}'}, method='POST')
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)

TOOL_SCHEMA = {'name':'pixel_display_text','description':'在指定墨水屏显示标题和正文', 'parameters':{'type':'object','properties':{'device_id':{'type':'string'},'title':{'type':'string'},'body':{'type':'string'}},'required':['device_id','title','body'],'additionalProperties':False}}

if __name__ == '__main__':
    print(display_text('desk-01','你好，Pixel','这是来自 pixel-app 的第一条消息。'))
