Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")
shell.CurrentDirectory = "C:\Users\Cristian Alexis CR\Documents\Daemon\daemon-core"
javawPath = "javaw.exe"
If fso.FolderExists("C:\Program Files\Microsoft") Then
    For Each folder In fso.GetFolder("C:\Program Files\Microsoft").SubFolders
        If InStr(LCase(folder.Name), "jdk") > 0 Then
            javawPath = Chr(34) & folder.Path & "\bin\javaw.exe" & Chr(34)
        End If
    Next
End If
shell.Run javawPath & " -Xms32m -Xmx128m -XX:+UseSerialGC -jar " & Chr(34) & "C:\Users\Cristian Alexis CR\Documents\Daemon\daemon-core\build\libs\daemon-0.0.1-SNAPSHOT.jar" & Chr(34), 0, False