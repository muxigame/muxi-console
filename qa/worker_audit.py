"""Read-only PID-generation/Windows-handle audit for the reviewed worker entry points. Never terminates a process."""
from __future__ import annotations
import argparse
import ctypes
from ctypes import wintypes
import json
from pathlib import Path
import time
import zipfile
import psutil
from drive import Driver,read_json,sha

MAINS={"net.muxigame.console.runtime.WorkerMain","net.muxigame.console.runtime.LinkWorker","net.muxigame.console.runtime.LinkDsWorker","net.muxigame.console.runtime.Link3dsWorker"}


def watch(run,build,expect_link=False):
    d=Driver(run,build);root=d.root;marker=d.marker;parents={p["pid"]:(role,p)for role,p in marker["processes"].items()}
    path=root/"console-qa/emulator-workers.json"
    if path.exists():raise ValueError("Existing worker audit is immutable; use a new run")
    with zipfile.ZipFile(d.build.get("runtimeArtifact",d.build["product"])["path"])as product:runtime=__import__("hashlib").sha256(product.read("worker/runtime.jar")).hexdigest()
    kernel=ctypes.WinDLL("kernel32",use_last_error=True)
    kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD];kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.GetExitCodeProcess.argtypes=[wintypes.HANDLE,ctypes.POINTER(wintypes.DWORD)];kernel.GetExitCodeProcess.restype=wintypes.BOOL
    kernel.WaitForSingleObject.argtypes=[wintypes.HANDLE,wintypes.DWORD];kernel.WaitForSingleObject.restype=wintypes.DWORD
    kernel.GetProcessTimes.argtypes=[wintypes.HANDLE,ctypes.POINTER(wintypes.FILETIME),ctypes.POINTER(wintypes.FILETIME),ctypes.POINTER(wintypes.FILETIME),ctypes.POINTER(wintypes.FILETIME)];kernel.GetProcessTimes.restype=wintypes.BOOL
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    records={};handles={};errors=[];began=time.time();closed_at=None;report={}
    def save():
        report.update(schema=2,exitEvidence="Exact-generation retained Windows handle must be signaled; a reported exit code alone is insufficient",runId=marker["runId"],productSha256=d.build["product"]["sha256"],qaBuildSha256=sha(d.build_path),observerSha256=sha(Path(__file__)),observedAt=time.time(),workers=list(records.values()),errors=errors,processMutation=False,authenticationArgumentsRecorded=False)
        path.parent.mkdir(exist_ok=True);tmp=path.with_suffix(".tmp");tmp.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8");tmp.replace(path)
    try:
        print(json.dumps({"watching":str(root),"entryPoints":sorted(MAINS)}),flush=True)
        while True:
            current=read_json(root/"local-mc-owner.json")
            if current["runId"]!=marker["runId"]:raise ValueError("Run owner changed")
            for process in psutil.process_iter(["pid","name"]):
                if "java"not in(process.info.get("name")or"").lower():continue
                try:
                    args=process.cmdline();main=next((a for a in args if a in MAINS),None)
                    if main is None:continue
                    parent=parents.get(process.ppid());cwd=Path(process.cwd()).resolve()
                    if parent is None:
                        if root==cwd or root in cwd.parents:errors.append("Unattributed emulator in owned run: "+str(process.pid))
                        continue
                    role,p=parent;created=process.create_time();key=(process.pid,created)
                    if key in records:continue
                    if role=="server"or abs(psutil.Process(p["pid"]).create_time()-p["processCreateTime"])>.1 or cwd!=root/role:raise ValueError("Worker parent generation/cwd differs")
                    native=[Path(a.split("=",1)[1]).absolute()for a in args if a.startswith("-Djna.tmpdir=")]
                    if len(native)!=1 or root/role not in native[0].resolve().parents:raise ValueError("Worker native directory is outside its actual role")
                    cp=args[args.index("-cp")+1].split(";");runtime_files=[Path(a).resolve(strict=True)for a in cp if Path(a).name=="runtime.jar"]
                    if len(runtime_files)!=1 or root/role not in runtime_files[0].parents or sha(runtime_files[0])!=runtime:raise ValueError("Worker runtime is not the exact candidate payload")
                    handle=kernel.OpenProcess(0x1000|0x100000,False,process.pid)
                    if not handle:raise OSError("Unable to hold read-only worker process handle")
                    fresh=psutil.Process(process.pid)
                    if abs(fresh.create_time()-created)>.002 or fresh.ppid()!=p["pid"]:kernel.CloseHandle(handle);raise ValueError("Worker generation changed during handle acquisition")
                    creation=wintypes.FILETIME();ended=wintypes.FILETIME();system=wintypes.FILETIME();user=wintypes.FILETIME()
                    if not kernel.GetProcessTimes(handle,ctypes.byref(creation),ctypes.byref(ended),ctypes.byref(system),ctypes.byref(user)):kernel.CloseHandle(handle);raise OSError("Worker creation FILETIME unavailable")
                    filetime=(creation.dwHighDateTime<<32)|creation.dwLowDateTime
                    if abs(filetime/10000000-11644473600-created)>.002:kernel.CloseHandle(handle);raise ValueError("Held worker generation differs from observed PID")
                    records[key]={"pid":process.pid,"createTime":created,"creationFileTime":filetime,"role":role,"parentPid":p["pid"],"parentCreateTime":p["processCreateTime"],"main":main,"exe":process.exe(),"cwd":str(cwd),"nativeDirectory":str(native[0]),"runtimeSha256":runtime,"firstObservedAt":time.time(),"exitCode":None,"exitSignalConfirmed":False}
                    handles[key]=handle
                except psutil.NoSuchProcess:continue
                except (psutil.AccessDenied,OSError,ValueError)as failure:errors.append(type(failure).__name__+": owned worker inspection failed")
            for key,handle in list(handles.items()):
                code=wintypes.DWORD()
                if not kernel.GetExitCodeProcess(handle,ctypes.byref(code)):raise OSError("Worker exit-code handle became unavailable")
                wait=kernel.WaitForSingleObject(handle,0)
                if code.value!=259:records[key]["reportedExitCode"]=code.value
                if wait==0:
                    if not kernel.GetExitCodeProcess(handle,ctypes.byref(code)):raise OSError("Signaled worker exit code unavailable")
                    records[key].update(exitCode=code.value,exitedAt=time.time(),exitSignalConfirmed=True);kernel.CloseHandle(handle);del handles[key]
                elif wait!=258:raise OSError("Worker termination signal could not be inspected")
            gone=True
            for p in marker["processes"].values():
                try:
                    if abs(psutil.Process(p["pid"]).create_time()-p["processCreateTime"])<=.1:gone=False
                except psutil.NoSuchProcess:pass
            if gone:
                if closed_at is None:closed_at=time.monotonic()
                if(root/"run-result.json").exists()or time.monotonic()-closed_at>30:
                    result=read_json(root/"run-result.json")if(root/"run-result.json").exists()else{}
                    for row in records.values():row["nativeTempCleaned"]=not Path(row["nativeDirectory"]).exists()
                    mains={r["main"]for r in records.values()};report.update(closed=True,runnerNormalExit=result.get("normalExit")is True,remaining=[r for k,r in records.items()if k in handles])
                    report["resourceComplete"]=bool(records)and not handles and not errors and result.get("runId")==marker["runId"]and result.get("normalExit")is True and all(r["exitSignalConfirmed"]and r["exitCode"]==0 and r["nativeTempCleaned"]for r in records.values())
                    report["coverageComplete"]=(not expect_link or "net.muxigame.console.runtime.WorkerMain"in mains and bool(mains&{"net.muxigame.console.runtime.LinkWorker","net.muxigame.console.runtime.LinkDsWorker","net.muxigame.console.runtime.Link3dsWorker"}))
                    report["complete"]=report["resourceComplete"]and report["coverageComplete"]
                    save();print(json.dumps({"complete":report["complete"],"workers":len(records),"mains":sorted(mains),"path":str(path)}),flush=True);return report
            elif time.time()-began>7200:raise TimeoutError("Worker audit exceeded this run's maximum observation window")
            report.update(closed=False,complete=False);save();time.sleep(.25)
    finally:
        for handle in handles.values():kernel.CloseHandle(handle)


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--run",type=Path,required=True);parser.add_argument("--build",type=Path,required=True);parser.add_argument("--expect-link",action="store_true");args=parser.parse_args();result=watch(args.run,args.build,args.expect_link);raise SystemExit(0 if result["complete"]else 1)
