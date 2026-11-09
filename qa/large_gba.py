"""Private QA derivation: retain the verified first-party ARM diagnostic, append only erased-ROM bytes."""
from pathlib import Path
import hashlib

SIZE=8*1024*1024

def derive(base:Path,target:Path,size:int=SIZE)->dict:
    if size not in (8*1024*1024,32*1024*1024):raise ValueError("Use the original 8MiB or maximum GBA 32MiB fixture")
    content=base.read_bytes()
    if len(content)!=65536:raise ValueError("Expected the already verified 64KiB first-party diagnostic")
    target.parent.mkdir(parents=True,exist_ok=True)
    with target.open("xb")as output:
        output.write(content);remaining=size-len(content);padding=b"\xff"*32768
        while remaining:
            count=min(remaining,len(padding));output.write(padding[:count]);remaining-=count
    with target.open("rb")as source:
        if source.read(len(content))!=content:raise ValueError("Original diagnostic bytes changed")
        digest=hashlib.sha256(content)
        while block:=source.read(32768):
            if block!=b"\xff"*len(block):raise ValueError("Only FF padding is permitted")
            digest.update(block)
    if target.stat().st_size!=size:raise ValueError("Derived fixture size differs")
    return {"path":str(target),"sha256":digest.hexdigest(),"bytes":size,"basePath":str(base),"baseSha256":hashlib.sha256(content).hexdigest(),"baseBytes":len(content),"paddingByte":255,"paddingBytes":size-len(content)}
