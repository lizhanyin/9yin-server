$cmd = '/c ""D:\Users\Python\Python314\python.exe" "G:\pojie\ai\9yin-server\tools\smoke\dump_fxgame.py" > "G:\pojie\ai\9yin-server\tools\smoke\dump_out.txt" 2>&1"'
try {
  Start-Process -FilePath 'cmd.exe' -ArgumentList $cmd -Verb RunAs -Wait
  Write-Output 'spawned-ok'
} catch {
  Write-Output ('ERR: ' + $_.Exception.Message)
}
