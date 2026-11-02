Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")
currentDir = fso.GetParentFolderName(WScript.ScriptFullName)
shell.CurrentDirectory = currentDir

' Eliminar cualquier icono del Escritorio para mantenerlo limpio
desktopPath = shell.SpecialFolders("Desktop")
If fso.FileExists(desktopPath & "\Daemon AI.lnk") Then fso.DeleteFile(desktopPath & "\Daemon AI.lnk")

' Registrar Daemon AI como Aplicacion en el Menu Inicio de Windows
programsPath = shell.SpecialFolders("Programs")
Set appShortcut = shell.CreateShortcut(programsPath & "\Daemon AI.lnk")
appShortcut.TargetPath = "wscript.exe"
appShortcut.Arguments = Chr(34) & WScript.ScriptFullName & Chr(34)
appShortcut.WorkingDirectory = currentDir
appShortcut.IconLocation = "shell32.dll,13"
appShortcut.Save

' Registrar en el Inicio Automatico al prender la PC
startupPath = shell.SpecialFolders("Startup")
Set bootShortcut = shell.CreateShortcut(startupPath & "\Daemon AI.lnk")
bootShortcut.TargetPath = "wscript.exe"
bootShortcut.Arguments = Chr(34) & WScript.ScriptFullName & Chr(34)
bootShortcut.WorkingDirectory = currentDir
bootShortcut.IconLocation = "shell32.dll,13"
bootShortcut.Save

javawPath = "javaw.exe"
If fso.FolderExists("C:\Program Files\Microsoft") Then
    For Each folder In fso.GetFolder("C:\Program Files\Microsoft").SubFolders
        If InStr(LCase(folder.Name), "jdk") > 0 Then
            javawPath = Chr(34) & folder.Path & "\bin\javaw.exe" & Chr(34)
        End If
    Next
End If

jarPath = currentDir & "\build\libs\daemon-0.0.1-SNAPSHOT.jar"
If Not fso.FileExists(jarPath) Then jarPath = currentDir & "\daemon.jar"

shell.Run javawPath & " -Xms32m -Xmx128m -XX:+UseSerialGC -jar " & Chr(34) & jarPath & Chr(34), 0, False
