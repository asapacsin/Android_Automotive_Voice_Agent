# ADR-017 gate Q-1 / Q-2 batch. Needs DASHSCOPE_API_KEY and DASHSCOPE_WORKSPACE_ID in the environment.
$ErrorActionPreference = "Stop"
$p = Join-Path $PSScriptRoot "qwen_probe.py"
if (-not $env:OUT) { $env:OUT = Join-Path $PSScriptRoot "out" }
Remove-Item Env:CLIENT_CANCEL -ErrorAction SilentlyContinue
foreach ($c in "can_you_talk.pcm", "ctx_too_hot.pcm", "chat_q.pcm") { python $p latency $c 5 }
python $p tool live_weather.pcm
python $p tool ac_on.pcm
python $p text
for ($i = 0; $i -lt 5; $i++) { python $p barge can_you_talk.pcm shut_up_zh.pcm }
$env:CLIENT_CANCEL = "1"
for ($i = 0; $i -lt 5; $i++) { python $p barge can_you_talk.pcm shut_up_zh.pcm }
Remove-Item Env:CLIENT_CANCEL
python $p barge can_you_talk.pcm noise_cough.pcm
python $p barge can_you_talk.pcm noise_knock.pcm
python $p say
python $p compare
