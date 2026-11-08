#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Export portable public corresponding-source views without changing private evidence or DLLs."""
from __future__ import annotations
import gzip
import hashlib
import io
import json
from pathlib import Path, PureWindowsPath
import re
import subprocess
import sys
import tarfile

REPO=Path(__file__).resolve().parents[1]
WORKSPACE=REPO.parent
PATH_ENTRIES=["${LLVM_BIN}","${MAKE_BIN}","${GIT_USR_BIN}","${GIT_MINGW_BIN}","${PYTHON_BIN}","${WINDOWS_SYSTEM32}"]


def digest(data):return hashlib.sha256(data).hexdigest()


def portable(value,key=""):
    if key=="PATH":return PATH_ENTRIES
    if isinstance(value,dict):return {k:portable(v,k)for k,v in value.items()}
    if isinstance(value,list):return [portable(v)for v in value]
    if not isinstance(value,str):return value
    value=value.replace(str(WORKSPACE),"${WORKSPACE}").replace(WORKSPACE.as_posix(),"${WORKSPACE}")
    value=re.sub(r"C:[/\\]PROGRA~1[/\\]Git[/\\]usr[/\\]bin[/\\]sh\.exe","${GIT_USR_BIN}/sh.exe",value,flags=re.I)
    value=re.sub(r"C:[/\\]ProgramData[/\\]anaconda3[/\\]python\.exe","${PYTHON}",value,flags=re.I)
    if re.match(r"^[A-Za-z]:[/\\]",value):value="${HOST_TOOL}/"+PureWindowsPath(value).name
    return value


def encoded(value):return (json.dumps(value,ensure_ascii=False,indent=2)+"\n").encode("utf-8")


def export(name):
    root=WORKSPACE/"test-environment/cache/muxi-console"/("rebuild-"+name)
    original_path=root/"rebuild-report.json";original_raw=original_path.read_bytes();original=json.loads(original_raw)
    source_archive=Path(original["sourceArchive"]["path"])
    assert digest(source_archive.read_bytes())==original["sourceArchive"]["sha256"]
    report=portable(original)
    report["buildScript"]["path"]="rebuild_cores.py"
    report["library"]["path"]=Path(original["library"]["path"]).name
    report["archive"]["path"]=Path(original["archive"]["path"]).name
    report.pop("sourceArchive")
    report["publicView"]={"privateOriginalReportSha256":digest(original_raw),"inheritedPathOmitted":True,"hostPathsPortable":True,
                          "logBodiesIncluded":False,"dllAndZipUnchanged":True}
    report["build"]["notes"]=["Actual native objects were compiled with the preserved historical driver; the final driver performed a deterministic timestamp-zero relink.",
        "No semantic native source patch is applied. Build variables and the BlastEm byte-embedding preparation are recorded explicitly."]
    if name=="blastem":
        report["build"]["notes"].append("Initial GNU make/MSYS sed backslash requoting failed during data embedding; the retained failure log is represented by its hash only.")
    members={}
    with tarfile.open(source_archive,"r:gz")as source:
        for member in source:
            assert member.isfile()and not member.name.startswith("/")and ".."not in Path(member.name).parts
            payload=source.extractfile(member).read()
            if member.name.endswith(".json")and not member.name.startswith("source/"):
                payload=encoded(portable(json.loads(payload)))
            members[member.name]=(payload,member.mode)
    assert digest(members["rebuild_cores.py"][0])==report["buildScript"]["sha256"]
    report["publicView"]["historyMembers"]=[{"member":name,"sha256":digest(payload)}for name,(payload,_)in sorted(members.items())if name.startswith("history/")]
    # Preserve the actual derived C/H compiled in the initial build. The preferred
    # .cpu forms and the official generator remain present; generation can have
    # different ordering across Python hash seeds, so no bit-identical claim follows.
    snapshots=[]
    if name=="blastem":
        for path in sorted([*(root/"source").glob("*.c"),*(root/"source").glob("*.h")]):
            member="source/"+path.name
            if member not in members:
                payload=path.read_bytes();members[member]=(payload,0o644)
                snapshots.append({"member":member,"size":len(payload),"sha256":digest(payload),"origin":"Actual generated source compiled by the recorded build; preferred forms and generators are also included"})
    report["generatedSourceSnapshots"]=snapshots
    proof_path=root/"offline-rebuild-check.json"
    if proof_path.exists():
        proof=portable(json.loads(proof_path.read_text(encoding="utf-8")))
        independent=root/"offline-check/source"/("libblastem.dll"if name=="blastem"else "mednafen_pce_fast_libretro.dll")
        probe=subprocess.run([sys.executable,"-B",str(REPO/"tools/rebuild_cores.py"),"--probe",str(independent)],capture_output=True,text=True,timeout=30)
        if probe.returncode:raise ValueError("Independent rebuilt DLL did not pass the basic API probe")
        proof["basicApiProbe"]=dict(json.loads(probe.stdout),normalExit=True)
        report["independentBuild"]={"resultMember":"INDEPENDENT-BUILD.json","byteIdentical":proof["byteIdentical"],"recordedExitCode":proof["exitCode"],
            "note":"The delivered archive compiled independently without source fetch or reused objects. An exit 1 may reflect the strict byte-comparison guard after successful compilation; actual hashes remain in the evidence."
            if name=="blastem"else "Exact independent build result retained without changing the original final DLL or ZIP."}
        members["INDEPENDENT-BUILD.json"]=(encoded(proof),0o644)
    members["BUILD.json"]=(encoded(report),0o644)
    instructions=members["BUILDING.md"][0].decode("utf-8")
    instructions=instructions.replace("Check the resulting DLL SHA256 against BUILD.json library.sha256.",
        "The driver compares the resulting DLL SHA256 against BUILD.json library.sha256 after compilation. A mismatch is reported as a nonzero exit; the compiled DLL remains available. Bit-identical reproduction is not promised by this source delivery.")
    instructions+=("\nPublic build records replace inherited host PATH with portable tool roles; no unrelated host directories or raw private build logs are shipped. "
        "The actual historical and final build-driver bytes remain included for provenance. "
        "BlastEm's preferred CPU DSL and actual generated C/H snapshots are both supplied; the earlier independently regenerated uPD78K2 C had different ordering and did not match the released DLL hash. "
        "Do not interpret a new build as proof about any old rolling binary.\n")
    members["BUILDING.md"]=(instructions.encode("utf-8"),0o644)
    archive=root/(name+"-corresponding-public-source.tar.gz")
    with archive.open("xb")as raw,gzip.GzipFile(filename="",mode="wb",fileobj=raw,mtime=0)as compressed,tarfile.open(fileobj=compressed,mode="w")as target:
        for name_in_archive,(payload,mode)in sorted(members.items()):
            entry=tarfile.TarInfo(name_in_archive);entry.mode=mode;entry.mtime=0;entry.size=len(payload)
            target.addfile(entry,io.BytesIO(payload))
    report["sourceArchive"]={"path":archive.name,"size":archive.stat().st_size,"sha256":digest(archive.read_bytes()),"format":"tar.gz",
        "patchesApplied":bool(report["patches"]),"completePreferredSource":True,"buildInstructions":"BUILDING.md","sourceDirectory":"source","publicSanitized":True}
    report_path=root/"rebuild-public-report.json"
    with report_path.open("xb")as stream:stream.write(encoded(report))
    # Check every exported record rather than trusting a blacklist of known paths.
    for member,(payload,_)in members.items():
        if member.endswith(".json")and not member.startswith("source/"):
            serialized=payload.decode("utf-8")
            assert "C:\\\\Users\\\\"not in serialized and "C:/Users/"not in serialized
            assert "anaconda3"not in serialized
    assert original_path.read_bytes()==original_raw
    assert digest(Path(original["library"]["path"]).read_bytes())==original["library"]["sha256"]
    assert digest(Path(original["archive"]["path"]).read_bytes())==original["archive"]["sha256"]
    print(json.dumps({"core":name,"reportPath":str(report_path),"reportSha256":digest(report_path.read_bytes()),"sourceArchive":report["sourceArchive"]}))


if __name__=="__main__":
    for core in ("blastem","pce"):export(core)
