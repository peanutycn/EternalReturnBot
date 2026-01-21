<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
    <title>${title?html}</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { background: #f5f5f5; color: #1f2328; font-family: "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", sans-serif; }
        #help-container {
            width: 760px;
            padding: 16px;
            background: #ffffff;
            border: 1px solid rgb(230, 230, 230);
            border-radius: 12px;
        }
        .title {
            font-size: 18px;
            font-weight: 800;
            margin-bottom: 10px;
        }
        .subtitle {
            font-size: 12px;
            color: #6b7280;
            margin-bottom: 12px;
        }
        pre {
            white-space: pre-wrap;
            word-break: break-word;
            font-size: 13px;
            line-height: 1.45;
            color: #111827;
            font-family: "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", sans-serif;
        }
        .hint {
            margin-top: 10px;
            font-size: 12px;
            color: #6b7280;
        }
    </style>
</head>
<body>
<div id="help-container">
    <div class="title">${title?html}</div>
    <div class="subtitle">建议在群里直接发送指令（无需 /）</div>
    <pre>${text?html}</pre>
    <div class="hint">若图片显示异常，可用“反馈 &lt;内容&gt;”转发给管理员</div>
</div>
</body>
</html>

