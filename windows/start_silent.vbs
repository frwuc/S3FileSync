' S3FileSync - 无窗口启动（pythonw 常驻托盘）
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
' 取本脚本所在目录（即 windows/ 目录），保证换位置也能启动
scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
sh.CurrentDirectory = scriptDir
sh.Run """D:\02soft\python312\pythonw.exe"" """ & scriptDir & "\run.pyw""", 0, False
