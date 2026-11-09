"""Build separate, disposable server/client QA mods from this repository's own sources."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile
import io
import tomllib
import struct

REPO = Path(__file__).resolve().parents[1]
WORKSPACE = REPO.parent
sys.path.insert(0, str(WORKSPACE / "better-mc-remake/scripts"))
import local_mc_runtime as sdk


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(32768), b""):
            digest.update(block)
    return digest.hexdigest()


def wii_wbfs_fixture(report_path: Path, diagnostics: Path, dol: dict) -> tuple[dict,dict,dict]:
    """Admit only our source-bound plain original disc and independently decode its WBFS mapping."""
    report_path=report_path.resolve(strict=True)
    if not report_path.is_relative_to(REPO/'build')or report_path.name!='disc-diagnostic.json':raise ValueError('Original Wii disc report must be a repository build')
    report=json.loads(report_path.read_text(encoding='utf-8'))
    if report.get('original')is not True or report.get('structuralChecksPassed')is not True or any(report.get(k)is not False for k in('vendorApploaderIncluded','biosIncluded','keysIncluded','gameAssetsIncluded','partitionEncrypted','partitionHasHashes')):raise ValueError('Only our original plain no-vendor Wii disc fixture is accepted')
    if report.get('originalDiagnostics')!={'path':str(diagnostics),'sha256':sha(diagnostics)}or report.get('originalDol')!={k:dol[k]for k in('path','bytes','sha256')}:raise ValueError('Wii disc does not derive from this exact original DOL report')
    tool=REPO/'tools/make_wii_disc_diagnostic.py';loader=REPO/'tests/runtime/diagnostics/expansion/disc/apploader.c';app=report.get('apploader',{});binary=report_path.parent/'apploader.bin'
    if report.get('toolSha256')!=sha(tool)or app.get('source')!=loader.relative_to(REPO).as_posix()or app.get('sourceSha256')!=sha(loader)or binary.stat().st_size!=app.get('bytes')or sha(binary)!=app.get('sha256'):raise ValueError('Original disc wrapper or authored apploader differs')
    inputs={report_path:sha(report_path),tool:sha(tool),loader:sha(loader),binary:sha(binary)};files={}
    for row in report.get('programs',[]):
        path=Path(row['path']).resolve(strict=True)
        if path.parent!=report_path.parent or path.suffix not in('.iso','.wbfs')or path.suffix in files or not 0<path.stat().st_size<=4*1024*1024 or path.stat().st_size!=row['bytes']or sha(path)!=row['sha256']:raise ValueError('Exact authored ISO/WBFS file identity required')
        files[path.suffix]=(path,row);inputs[path]=row['sha256']
    if set(files)!={'.iso','.wbfs'}:raise ValueError('Original disc evidence must contain one ISO and one WBFS')
    iso=files['.iso'][0].read_bytes();wbfs=files['.wbfs'][0].read_bytes();be32=lambda data,offset:struct.unpack_from('>I',data,offset)[0]
    if len(iso)!=0x100000 or len(wbfs)!=0x400000 or wbfs[:4]!=b'WBFS'or be32(wbfs,4)!=len(wbfs)//512 or wbfs[8:10]!=bytes((9,21))or wbfs[12]!=1:raise ValueError('Actual original WBFS container header required')
    mapped=struct.unpack_from('>H',wbfs,768)[0];block=1<<wbfs[9]
    if mapped!=1 or wbfs[512:768]!=iso[:256]or wbfs[mapped*block:mapped*block+len(iso)]!=iso:raise ValueError('WBFS mapping does not decode to the exact authored ISO')
    if iso[:6]!=b'MXIE00'or be32(iso,0x18)!=0x5d1c9ea3 or iso[0x60:0x62]!=bytes((1,1))or be32(iso,0x40000)!=1 or be32(iso,0x40004)*4!=0x40020 or be32(iso,0x40024)!=0:raise ValueError('Expected one original unencrypted Wii data partition')
    partition=be32(iso,0x40020)*4;data=partition+be32(iso,partition+0x2b8)*4;dol_offset=data+be32(iso,data+0x420)*4;dol_bytes=Path(dol['path']).read_bytes();app_offset=data+0x2440
    if partition!=0x50000 or data!=0x70000 or dol_offset!=data+0x10000 or iso[dol_offset:dol_offset+len(dol_bytes)]!=dol_bytes or iso[app_offset:app_offset+10]!=b'MUXI-TEST\0'or be32(iso,app_offset+0x10)!=app['entry']or be32(iso,app_offset+0x14)!=app['bytes']or iso[app_offset+32:app_offset+32+app['bytes']]!=binary.read_bytes():raise ValueError('Wii partition does not contain our exact DOL and authored apploader')
    row=files['.wbfs'][1]
    return {**row,'path':str(files['.wbfs'][0]),'suffix':'wbfs'},inputs,dict(report=str(report_path),reportSha256=sha(report_path),structuralChecksPassed=True,originalDol=dol,scope='Authored WBFS mapped disc only; no commercial game or vendor assets')


def build(name: str, product: Path, rom: Path, gb_rom: Path, pixel_review: bool = False, cartridge_review: bool = False, plan_path: Path | None = None, link_review: bool = False, media_probe: Path | None = None, expansion_diagnostics: Path | None = None, large_rom_mib: int = 8, gba_link_review: bool = False, nds_link_review: bool = False, n3ds_link_review: bool = False, latency_review: bool = False, latency_probe: Path | None = None, runtime: Path | None = None, jei: Path | None = None, recipe_review: bool = False, wii_wbfs_diagnostic: Path | None = None) -> Path:
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name):
        raise ValueError("Use a new lowercase QA build name")
    if large_rom_mib not in (8,32) or large_rom_mib!=8 and not cartridge_review:raise ValueError('Large fixture must be an explicit cartridge review at 8 or 32MiB')
    product, rom, gb_rom = product.resolve(strict=True), rom.resolve(strict=True), gb_rom.resolve(strict=True)
    runtime_artifact=None;jei_artifact=None
    if runtime is not None:
        runtime=runtime.resolve(strict=True)
        if not runtime.is_relative_to(REPO/'build'):raise ValueError('Runtime must be a frozen repository build artifact')
        with zipfile.ZipFile(runtime)as pack:
            meta=tomllib.loads(pack.read('META-INF/neoforge.mods.toml').decode('utf-8'))
            if {r['modId']for r in meta['mods']}!={'muxi_console_runtime'}or any(n.endswith('.class')for n in pack.namelist()):raise ValueError('Expected the independent class-free runtime resource mod')
            worker=pack.read('worker/runtime.jar');pins_runtime=json.loads(pack.read('META-INF/muxi/extract-hashes.json'))
            if hashlib.sha256(worker).hexdigest()!=pins_runtime['worker/runtime.jar']:raise ValueError('Runtime worker payload differs from its extraction manifest')
        runtime_artifact=dict(path=str(runtime),bytes=runtime.stat().st_size,sha256=sha(runtime),version=str(meta['mods'][0]['version']),workerSha256=hashlib.sha256(worker).hexdigest(),modId='muxi_console_runtime',scope='client')
    if jei is not None:
        jei=jei.resolve(strict=True);clean=WORKSPACE/'minecraft/release/client/mods'
        if jei.parent!=clean or jei.name!='jei-1.21.1-neoforge-19.32.0.359.jar' or jei.stat().st_size!=1616910 or sha(jei)!='0169e38d4771b1a0a6bd9fc92734297105fff2f6ad8a4ed73ad38139b824ea54':raise ValueError('Use the exact formally distributed JEI client artifact')
        jei_artifact=dict(path=str(jei),bytes=jei.stat().st_size,sha256=sha(jei),version='19.32.0.359',modId='jei',scope='both')
    if recipe_review and (runtime_artifact is None or jei_artifact is None):raise ValueError('Recipe review requires explicit split runtime and exact JEI')
    if REPO / "build" not in product.parents or REPO / "build/runtime-smoke" not in rom.parents:
        raise ValueError("QA inputs must be the new repository's product and original runtime diagnostic")
    if any(os.environ.get(key) for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")):
        raise ValueError("Inherited Java options are not accepted")
    with zipfile.ZipFile(product) as jar:
        if "net/muxigame/console/MuxiConsole.class" not in jar.namelist():
            raise ValueError("Missing independent product entrypoint")
        catalog = jar.read("assets/muxi_console/model_catalog.json")
        link_core=json.loads(jar.read("META-INF/muxi/sameboy-link.json")) if link_review else None
        gba_link_core=json.loads(jar.read("META-INF/muxi/mgba-link.json")) if gba_link_review else None
        n3ds_link_core=json.loads(jar.read("META-INF/muxi/azahar-link.json")) if n3ds_link_review else None
        if n3ds_link_review and (hashlib.sha256(jar.read("cores/windows-x86_64/azahar_link.dll")).hexdigest()!=n3ds_link_core["platforms"]["windows-x86_64"]["librarySha256"] or n3ds_link_core["nativeApi"]["profileStateAbi"]!=2 or not n3ds_link_core["nativeDiagnostics"]["activeUdsCheckpointRestore"]):raise ValueError("Exact ABI2 active UDS restore proof required")
        nds_link_core=json.loads(jar.read("META-INF/muxi/melondsds-link.json")) if nds_link_review else None
        if nds_link_review and hashlib.sha256(jar.read("cores/windows-x86_64/melondsds_link.dll")).hexdigest()!=nds_link_core["platforms"]["windows-x86_64"]["librarySha256"]:raise ValueError("Packed DS bridge differs from its actual pin")
        if gba_link_review and hashlib.sha256(jar.read("cores/windows-x86_64/mgba_link.dll")).hexdigest()!=gba_link_core["platforms"]["windows-x86_64"]["librarySha256"]:raise ValueError("Packed GBA link binary differs from its candidate pin")
    plan_path = (plan_path or REPO / "build/model-review-plan.json").resolve(strict=True)
    if REPO/"build" not in plan_path.parents:raise ValueError("Model plan must be a new pinned repository build artifact")
    plan = plan_path.read_bytes()
    plan_json = json.loads(plan)
    product_catalog=json.loads(catalog)
    model_ids=sorted(row["cmd"] for row in [*product_catalog["entries"],*product_catalog["additional_styles"]])
    if not plan_json.get("native_only") or sorted(row["cmd"] for row in plan_json["stations"]) != model_ids:
        raise ValueError("Expected the complete independently authored native model plan")
    if catalog != (REPO / "common/src/main/resources/assets/muxi_console/model_catalog.json").read_bytes():
        raise ValueError("Product model catalog differs from current authored resources")
    if not rom.read_bytes().startswith(b"NES\x1a"):
        raise ValueError("Expected our newly authored iNES diagnostic")
    if REPO / "build/platform-smoke" not in gb_rom.parents or gb_rom.name != "original.gb" or gb_rom.stat().st_size != 32768:
        raise ValueError("GB fixture must be this repository's newly authored diagnostic")
    n64_rom = REPO / "build/platform-smoke/original-vi.z64"
    n64 = n64_rom.read_bytes()
    if n64[:4] != bytes.fromhex("80371240") or len(n64) != 0x101000:
        raise ValueError("Expected the original no-vendor-IPL3 N64 diagnostic")
    gba_rom = REPO / "build/platform-smoke/regression.gba"
    gba = None
    if pixel_review or cartridge_review or recipe_review:
        gba = gba_rom.read_bytes()
        baseline = json.loads((REPO / "build/platform-smoke/gba-result.json").read_text(encoding="utf-8"))
        if len(gba) != 65536 or hashlib.sha256(gba).hexdigest() != baseline.get("contentSha256") or baseline.get("exitCode") != 0:
            raise ValueError("GBA fixture differs from the already verified first-party ARM diagnostic")
    link_media={};link_report=None;link_boot=None
    gba_link_media={};gba_link_inputs={};gba_link_baselines=[]
    nds_link_media={};nds_link_inputs={};nds_link_baselines=[]
    n3ds_link_media={};n3ds_link_inputs={};n3ds_link_baselines=[]
    media=None;media_inputs={};expansion={};expansion_inputs={};wbfs_evidence=None
    if wii_wbfs_diagnostic is not None and expansion_diagnostics is None:raise ValueError('WBFS fixture also requires its original expansion diagnostics report')
    if expansion_diagnostics is not None:
        expansion_diagnostics=expansion_diagnostics.resolve(strict=True)
        if not expansion_diagnostics.is_relative_to(REPO/'build') or expansion_diagnostics.name!='diagnostics.json':raise ValueError('Expansion diagnostics must be an original repository build report')
        original=json.loads(expansion_diagnostics.read_text(encoding='utf-8'))
        if original.get('original')is not True or original.get('biosIncluded')is not False or original.get('gameAssetsIncluded')is not False:raise ValueError('Only our original no-BIOS expansion diagnostics may be embedded')
        expected_sources={'tests/runtime/diagnostics/expansion/'+name for name in('n3ds.c','nds7.c','nds9.c','wii.c','nds7.S','nds9.S')}|{'tools/make_expansion_diagnostics.py','tools/prepare_expansion_sdk.py'}
        if set(original.get('sources',{}))!=expected_sources or any(sha(REPO/path)!=digest for path,digest in original['sources'].items()):raise ValueError('Original expansion generator/source pins changed')
        expansion_inputs={expansion_diagnostics:sha(expansion_diagnostics),**{REPO/path:digest for path,digest in original['sources'].items()}}
        for platform,suffix in(('nds','nds'),('n3ds','3dsx'),('wii','dol')):
            rows=[row for row in original['outputs']if Path(row['path']).name=='muxi-input.'+suffix]
            if len(rows)!=1:raise ValueError('Exactly three original expansion programs required')
            row=rows[0];path=Path(row['path']).resolve(strict=True)
            if path.parent!=expansion_diagnostics.parent or not 0<path.stat().st_size<=4*1024*1024 or path.stat().st_size!=row['bytes']or sha(path)!=row['sha256']:raise ValueError('Original expansion ROM differs')
            expansion[platform]={**row,'path':str(path),'suffix':suffix};expansion_inputs[path]=row['sha256']
        if len(original['outputs'])!=3:raise ValueError('Unexpected expansion program in fixture report')
        if wii_wbfs_diagnostic is not None:
            expansion['wii'],wbfs_inputs,wbfs_evidence=wii_wbfs_fixture(wii_wbfs_diagnostic,expansion_diagnostics,expansion['wii']);expansion_inputs.update(wbfs_inputs)
    if media_probe is not None:
        media_probe=media_probe.resolve(strict=True)
        if not media_probe.is_relative_to(REPO/'build'):raise ValueError('Media probe must be a fixed original repository build')
        media_report=json.loads(media_probe.read_text(encoding='utf-8'))
        media_result_path=media_probe.parent/'result.json';media_result=json.loads(media_result_path.read_text(encoding='utf-8'))
        if media_report.get('exitCode')!=0 or media_report.get('product',{}).get('sha256')!=sha(product) or media_result.get('exitCode')!=0 or media_result.get('forced') or not media_result.get('nativeTempCleaned') or media_result.get('checks',0)<10 or media_result.get('nonzeroPcmSamples',0)<1000:raise ValueError('Audible media baseline is not verified for this exact candidate')
        media_path=Path(media_report['diagnosticRom']['path']).resolve(strict=True)
        if media_path.parent!=media_probe.parent or sha(media_path)!=media_report['diagnosticRom']['sha256']:raise ValueError('Original media ROM changed')
        sys.path.insert(0,str(REPO/'tools'));from runtime_smoke import diagnostic_rom
        if media_path.read_bytes()!=diagnostic_rom(True):raise ValueError('Media diagnostic differs from original generator')
        media_inputs={media_probe:sha(media_probe),media_result_path:sha(media_result_path),media_path:sha(media_path),REPO/'tools/runtime_smoke.py':media_report['source']['runtime_smoke.py'],REPO/'tests/runtime/NesMediaProbe.java':media_report['source']['NesMediaProbe.java']}
        media={**media_report['diagnosticRom'],'baselineResult':str(media_result_path),'baselineResultSha256':sha(media_result_path),'probe':str(media_probe),'probeSha256':sha(media_probe)}
    if link_review:
        sys.path.insert(0,str(REPO/"tools"))
        from link_diagnostic import diagnostic,boot
        baseline=REPO/"build/link-runtime-smoke-05/result/result.json";link_report=json.loads(baseline.read_text(encoding="utf-8"))
        if link_report.get("platform")!="gb" or link_report.get("bidirectionalSingleStateCompatibility") is not True or any(w.get("exitCode")!=0 or w.get("forced") or not w.get("nativeTempCleaned") for w in link_report["workers"]):raise ValueError("Original serial diagnostic baseline is incomplete")
        if link_report["bridgeSHA256"]!=link_core["platforms"]["windows-x86_64"]["librarySha256"] or link_report["baseSingleCoreSHA256"]!=link_core["baseSingleCoreCompatibility"]["librarySha256"]:raise ValueError("Candidate serial bridge differs from the baseline core")
        for n,label,challenge in [(0,"left",0x3c),(1,"right",0xa7)]:
            path=REPO/f"build/link-runtime-smoke-05/original-endpoint-{n+1}.gb";content=path.read_bytes()
            if content!=diagnostic(n,challenge):raise ValueError("Original serial diagnostic drift")
            link_media[label]=(path,content)
        link_boot=boot(False)
        if link_boot!=(REPO/"build/link-runtime-smoke-05/system/dmg_boot.bin").read_bytes():raise ValueError("Original diagnostic-only boot drift")
    if gba_link_review:
        sys.path.insert(0,str(REPO/"tools"));from link_gba_diagnostic import diagnostic as gba_link_diagnostic
        for baseline_name in ("link-gba-runtime-04","link-gba-runtime-05"):
            report_path=REPO/"build"/baseline_name/"result/result.json";report=json.loads(report_path.read_text(encoding="utf-8"))
            if report.get("platform")!="gba"or report.get("checks",0)<144 or not report.get("bidirectionalSingleStateCompatibility")or len(report["workers"])!=4 or any(w.get("exitCode")!=0 or w.get("forced")or not w.get("nativeTempCleaned")for w in report["workers"])or report["bridgeSHA256"]!=gba_link_core["platforms"]["windows-x86_64"]["librarySha256"]or report["baseSingleCoreSHA256"]!=gba_link_core["baseSingleCoreCompatibility"]["librarySha256"]:raise ValueError("Exact normal/reversed GBA link native baseline required")
            gba_link_inputs[report_path]=sha(report_path);gba_link_baselines.append(dict(path=str(report_path),sha256=sha(report_path),checks=report["checks"]))
        generator=REPO/"tools/link_gba_diagnostic.py";gba_link_inputs[generator]=sha(generator)
        for n,label in enumerate(("left","right")):
            path=REPO/f"build/link-gba-runtime-04/original-endpoint-{n+1}.gba";content=path.read_bytes()
            if len(content)!=65536 or content!=gba_link_diagnostic(n):raise ValueError("Original independent GBA serial ROM drift")
            gba_link_media[label]=(path,content);gba_link_inputs[path]=sha(path)
    if nds_link_review:
        diagnostic_report=REPO/'build/link-ds-diagnostic-04/diagnostics.json';original_ds=json.loads(diagnostic_report.read_text(encoding='utf-8'))
        if original_ds.get('original')is not True or original_ds.get('vendorBIOSBundled')is not False:raise ValueError('Only original no-vendor-BIOS DS diagnostics are accepted')
        nds_link_inputs[diagnostic_report]=sha(diagnostic_report)
        for source_name,digest in original_ds['sourceInputs'].items():
            path=REPO/'tests/link/diagnostics'/source_name
            if source_name not in ('nds7.c','nds7.S','nds9.c','nds9.S')or sha(path)!=digest:raise ValueError('Original DS source differs')
            nds_link_inputs[path]=digest
        for n,label in enumerate(('left','right')):
            row=original_ds['outputs'][n];path=Path(row['path']).resolve(strict=True)
            if path.parent!=diagnostic_report.parent or row['role']!=n or sha(path)!=row['sha256']or path.stat().st_size!=row['bytes']:raise ValueError('Original DS image differs')
            nds_link_media[label]=(path,path.read_bytes());nds_link_inputs[path]=row['sha256']
        for native_name,count,path in [('core',10,REPO/'build/link-ds-safe-complete-05/core.log'),('group',14,REPO/'build/link-ds-safe-complete-05/group.log'),('single-compat',10,REPO/'build/link-ds-safe-single-new-01/run.log')]:
            rows=[json.loads(line)for line in path.read_text(encoding='utf-8').splitlines()if line.startswith('{')];report=rows[-1]
            bridge=report.get('coreSHA256',report.get('actualBridgeSHA256'))
            if report.get('checks')!=count or bridge!=nds_link_core['platforms']['windows-x86_64']['librarySha256']:raise ValueError('DS actual native baseline differs from candidate')
            if native_name=='core'and(not report.get('naturalExit')or not report.get('wirelessHardwareExchangeProven')):raise ValueError('Actual wireless CPU challenge is required')
            if native_name=='group'and(report.get('naturalWorkers')!=4 or sum(report['pendingCounts_MP_transport'])<1):raise ValueError('Nonempty complete wireless queue cold restore is required')
            if native_name=='single-compat'and(not report.get('bidirectionalSingleStateCompatibility')or report.get('baseSingleFormatSHA256')!=nds_link_core['baseSingleCoreCompatibility']['librarySha256']or report.get('actualInputCounters')!=[1,2,3]):raise ValueError('Actual single-state CPU compatibility is required')
            nds_link_inputs[path]=sha(path);nds_link_baselines.append(dict(path=str(path),sha256=sha(path),checks=count))
        endurance_root=REPO/'build/link-ds-endurance-foreign-01';endurance_path=endurance_root/'data/result.json';endurance=json.loads(endurance_path.read_text(encoding='utf-8'));invocation_path=endurance_root/'invocation.json';invocation=json.loads(invocation_path.read_text(encoding='utf-8'))
        if not endurance.get('success')or not endurance.get('naturalExit')or endurance.get('framesCompleted',0)<10000 or endurance.get('backgroundEndFrame',0)-endurance.get('backgroundStartFrame',0)<12 or invocation['inputs']['core']!=nds_link_core['platforms']['windows-x86_64']['librarySha256']or invocation['variant']!='baseline'or any('CompileCommand='in value or 'TieredStopAtLevel='in value for value in invocation['childJvmOptions']):raise ValueError('DS safe foreign-fault native baseline needs actual full-JIT endurance')
        for path in [endurance_path,invocation_path]:nds_link_inputs[path]=sha(path)
        for pid in endurance['workers']:
            jit=endurance_root/'private-crash-logs'/('jit-'+str(pid)+'.log')
            if not any(' 4 'in line and'com.sun.jna.Function::invoke'in line for line in jit.read_text(encoding='utf-8').splitlines()):raise ValueError('Actual child C2 compilation evidence is required')
            nds_link_inputs[jit]=sha(jit)
        nds_link_baselines.append(dict(path=str(endurance_path),sha256=sha(endurance_path),checks=0,actualFrames=endurance['framesCompleted'],kind='full-JIT endurance; separate from pure check counts'))
        nds_link_inputs[REPO/'tools/link_ds_diagnostic.py']=sha(REPO/'tools/link_ds_diagnostic.py')
    if n3ds_link_review:
        inputs_path=REPO/'build/3ds-radio-native-01/inputs.json';inputs=json.loads(inputs_path.read_text(encoding='utf-8'));source=REPO/'native/link/diagnostics/azahar_uds.c'
        if sha(source)!=inputs['firstPartyRom']['sha256']:raise ValueError('Original no-BIOS UDS source changed')
        n3ds_link_inputs.update({source:sha(source),inputs_path:sha(inputs_path)})
        for label,row in zip(('left','right'),inputs['roms']):
            path=inputs_path.parent/row['path']
            if row['role']!=(0 if label=='left'else 1)or sha(path)!=row['sha256']or path.stat().st_size!=row['bytes']:raise ValueError('Actual original UDS cartridge differs')
            n3ds_link_media[label]=(path,path.read_bytes());n3ds_link_inputs[path]=sha(path)
        for field in [n3ds_link_core['nativeDiagnostics'],n3ds_link_core['nativeDiagnostics']['productRuntime'],n3ds_link_core['nativeDiagnostics']['productPortable']]:
            path=REPO/field['report'];report=json.loads(path.read_text(encoding='utf-8'))
            if sha(path)!=field['reportSha256']or not report.get('complete')or report.get('coreSHA256',report.get('coreSha256'))!=n3ds_link_core['platforms']['windows-x86_64']['librarySha256']:raise ValueError('Actual 3DS native/product checkpoint proof differs')
            n3ds_link_inputs[path]=sha(path);n3ds_link_baselines.append(dict(path=str(path),sha256=sha(path),checks=field['checks']))
    latency=None;latency_inputs={};latency_rom=None;latency_nes=None
    if latency_probe is not None and not latency_review:raise ValueError('Latency proof requires explicit --latency-review')
    if latency_review:
        latency_probe=(latency_probe or REPO/'build/gba-realtime-baseline-03/inputs.json').resolve(strict=True)
        if not latency_probe.is_relative_to(REPO/'build'):raise ValueError('Latency proof must be this repository\'s fixed independent native baseline')
        baseline_inputs=json.loads(latency_probe.read_text(encoding='utf-8'));baseline_result_path=latency_probe.parent/'result.json';baseline_result=json.loads(baseline_result_path.read_text(encoding='utf-8'))
        closure=baseline_result.get('workerClosure',{})
        if (not baseline_result.get('complete')or baseline_result.get('measurementSeconds',0)<30 or baseline_result.get('perFrameSaveStateCalls')!=0
                or not closure.get('normalExit')or closure.get('exitCode')!=0 or closure.get('forced')or not closure.get('nativeTemporaryFilesCleaned')
                or not closure.get('actualOwnedProcessSignalRequired')or not all(c.get('passed')for c in baseline_result.get('checks',[]))):raise ValueError('Real paced ordinary GBA baseline and Windows closure are required')
        sys.path.insert(0,str(REPO/'tools'));from gba_realtime_diagnostic import diagnostic_rom as realtime_rom,contract as realtime_contract
        latency_rom=latency_probe.parent/'original-realtime.gba';latency_bytes=latency_rom.read_bytes()
        if len(latency_bytes)!=65536 or sha(latency_rom)!=baseline_inputs['romSha256']or latency_bytes!=realtime_rom():raise ValueError('Original realtime ROM differs from the actually executed baseline')
        if any(sha(REPO/p)!=digest for p,digest in baseline_inputs['sourceSHA256'].items()):raise ValueError('Realtime baseline generator/probe changed after execution')
        with zipfile.ZipFile(product)as jar:
            candidate_extract=json.loads(jar.read('META-INF/muxi/extract-hashes.json'))
            for entry in('cores/windows-x86_64/mgba.dll','worker/runtime.jar'):
                if candidate_extract[entry]!=baseline_inputs['extracted'][entry]['sha256']:raise ValueError('Latency candidate ordinary worker/core differs; obtain a new independent baseline')
        nes_probe_path=REPO/'build/nes-media-02/probe.json';nes_probe=json.loads(nes_probe_path.read_text(encoding='utf-8'));nes_result_path=nes_probe_path.parent/'result.json';nes_result=json.loads(nes_result_path.read_text(encoding='utf-8'));latency_nes=Path(nes_probe['diagnosticRom']['path']).resolve(strict=True)
        from runtime_smoke import diagnostic_rom as nes_rom
        if (nes_probe.get('exitCode')!=0 or nes_result.get('exitCode')!=0 or nes_result.get('forced')or not nes_result.get('nativeTempCleaned')
                or nes_result.get('nonzeroPcmSamples',0)<1000 or sha(latency_nes)!=nes_probe['diagnosticRom']['sha256']or latency_nes.read_bytes()!=nes_rom(True)
                or candidate_extract['cores/windows-x86_64/fceumm.dll']!=nes_probe['extracted']['cores/windows-x86_64/fceumm.dll']['sha256']):raise ValueError('Original audible NES remote-control fixture/core proof changed')
        latency_inputs={latency_probe:sha(latency_probe),baseline_result_path:sha(baseline_result_path),latency_rom:sha(latency_rom),nes_probe_path:sha(nes_probe_path),nes_result_path:sha(nes_result_path),latency_nes:sha(latency_nes),**{REPO/p:h for p,h in baseline_inputs['sourceSHA256'].items()},REPO/'tools/runtime_smoke.py':sha(REPO/'tools/runtime_smoke.py')}
        latency={'path':str(latency_rom),'sha256':sha(latency_rom),'size':len(latency_bytes),'probe':str(latency_probe),'probeSha256':sha(latency_probe),'baselineResult':str(baseline_result_path),'baselineResultSha256':sha(baseline_result_path),'contract':realtime_contract(),'nativeBaselineMeasurementSeconds':baseline_result['measurementSeconds'],'nativeAudioDevicePlayed':False,'nes':{'path':str(latency_nes),'sha256':sha(latency_nes),'size':latency_nes.stat().st_size,'probe':str(nes_probe_path),'probeSha256':sha(nes_probe_path),'baselineResultSha256':sha(nes_result_path)},'scope':'Ordinary GBA host-clock latency plus authored audible NES remote regression; no expansion/Link payload is implied'}
        # Only the already authored NDS program is needed for the native-files save seam.
        nds_report_path=REPO/'build/expansion-diagnostics-12/diagnostics.json';nds_report=json.loads(nds_report_path.read_text(encoding='utf-8'))
        if nds_report.get('original')is not True or nds_report.get('biosIncluded')is not False or nds_report.get('gameAssetsIncluded')is not False or any(sha(REPO/p)!=h for p,h in nds_report['sources'].items()):raise ValueError('Original NDS source provenance differs')
        nds_rows=[r for r in nds_report['outputs']if Path(r['path']).name=='muxi-input.nds']
        if len(nds_rows)!=1:raise ValueError('Exactly one authored NDS fixture required')
        nds_row=nds_rows[0];latency_nds=Path(nds_row['path']).resolve(strict=True)
        if latency_nds.parent!=nds_report_path.parent or latency_nds.stat().st_size!=nds_row['bytes']or sha(latency_nds)!=nds_row['sha256']or nds_row['bytes']>4*1024*1024:raise ValueError('Original NDS fixture bytes differ')
        latency['nds']={**nds_row,'path':str(latency_nds),'report':str(nds_report_path),'reportSha256':sha(nds_report_path)}
        latency_inputs.update({nds_report_path:sha(nds_report_path),latency_nds:sha(latency_nds),**{REPO/p:h for p,h in nds_report['sources'].items()}})
    empty_zip = io.BytesIO()
    with zipfile.ZipFile(empty_zip, "w"):
        pass
    pong = empty_zip.getvalue()
    out = REPO / "build/qa" / name
    out.mkdir(parents=True, exist_ok=False)
    large=None;pce=None
    if cartridge_review:
        from large_gba import derive
        large=derive(gba_rom,out/"diagnostics/large-original-arm.gba",large_rom_mib*1024*1024)
        large.update(generator="qa/large_gba.py",generatorSha256=sha(REPO/"qa/large_gba.py"))
        pce_path=REPO/"build/platform-smoke/original.pce";pce=pce_path.read_bytes();pce_result=REPO/"build/platform-smoke/pce-result.json";pce_baseline=json.loads(pce_result.read_text(encoding="utf-8"))
        if len(pce)!=32768 or hashlib.sha256(pce).hexdigest()!=pce_baseline.get("contentSha256")or pce_baseline.get("exitCode")!=0:raise ValueError("PCE fixture differs from verified first-party diagnostic")
    game = WORKSPACE / "test-environment/resources/client/game"
    server = WORKSPACE / "minecraft/release/server"
    jdk = WORKSPACE / "test-environment/toolchains/java-21/bin"
    metadata = sdk.client_metadata(game, "BatterMC5Remake")
    classpath = str(product) + os.pathsep + sdk.javac_classpath(server, game, "21.1.250", sdk.client_libraries(game, "BatterMC5Remake", metadata))
    if runtime is not None:classpath=str(runtime)+os.pathsep+classpath
    if jei is not None:classpath=str(jei)+os.pathsep+classpath
    sources = REPO / "qa/java/net/muxigame/consoleqa"
    pins = {"product.sha256": sha(product), "rom.sha256": sha(rom), "gb-rom.sha256": sha(gb_rom), "n64-rom.sha256": sha(n64_rom), "pong-zip.sha256": hashlib.sha256(pong).hexdigest(), "model-plan.sha256": sha(plan_path)}
    if gba is not None:
        pins["gba-rom.sha256"] = hashlib.sha256(gba).hexdigest()
    if runtime_artifact is not None:pins['runtime.sha256']=runtime_artifact['sha256']
    if jei_artifact is not None:pins['jei.sha256']=jei_artifact['sha256']
    for label,(path,content) in link_media.items():pins[f"link-{label}-rom.sha256"]=hashlib.sha256(content).hexdigest()
    for label,(path,content)in gba_link_media.items():pins[f"gba-link-{label}-rom.sha256"]=hashlib.sha256(content).hexdigest()
    for label,(path,content)in nds_link_media.items():pins[f"nds-link-{label}-rom.sha256"]=hashlib.sha256(content).hexdigest()
    for label,(path,content)in n3ds_link_media.items():pins[f"n3ds-link-{label}-rom.sha256"]=hashlib.sha256(content).hexdigest()
    if link_boot is not None:pins["link-boot.sha256"]=hashlib.sha256(link_boot).hexdigest()
    if media is not None:pins['media-rom.sha256']=media['sha256']
    if latency is not None:
        pins['latency.sha256']=latency['sha256']
        pins['expansion-nds-rom.sha256']=latency['nds']['sha256']
        if 'media-rom.sha256'in pins and pins['media-rom.sha256']!=latency['nes']['sha256']:raise ValueError('Conflicting authored NES fixtures')
        pins['media-rom.sha256']=latency['nes']['sha256']
    for platform,row in expansion.items():pins[f'expansion-{platform}-rom.sha256']=row['sha256'];pins[f'expansion-{platform}-rom.suffix']=row['suffix']
    if large is not None:
        pins["large-gba-rom.sha256"]=large["sha256"];pins["pce-rom.sha256"]=hashlib.sha256(pce).hexdigest()
    manifest = {"schema": 1, "product": {"path": str(product), "sha256": pins["product.sha256"]},
                "diagnosticRom": {"path": str(rom), "sha256": pins["rom.sha256"]}, "artifacts": {},
                "gbDiagnosticRom": {"path": str(gb_rom), "sha256": pins["gb-rom.sha256"]},
                "n64DiagnosticRom": {"path": str(n64_rom), "sha256": pins["n64-rom.sha256"]},
                "arcadeDiagnostic": {"source": "qa/build.py empty ZIP; MAME's built-in non-ROM Pong machine", "zipSha256": pins["pong-zip.sha256"]},
                "modelReviewPlan": {"path": str(plan_path), "sha256": sha(plan_path)},
                "minecraftTested": False, "physicalOSInput": False, "productionPublished": False}
    if runtime_artifact is not None:manifest['runtimeArtifact']=runtime_artifact
    if jei_artifact is not None:manifest['jeiArtifact']=jei_artifact
    if recipe_review:manifest['recipeReview']={'hardwareCount':25,'portableCount':12,'desktopCount':13,'realJeiUiRequired':True,'runtimeClientOnly':True}
    if gba is not None:
        manifest["gbaDiagnosticRom"] = {"path": str(gba_rom), "sha256": pins["gba-rom.sha256"],
                "generator": "tools/gba_diagnostic.py", "generatorSha256": sha(REPO / "tools/gba_diagnostic.py"),
                "baselineResult": "build/platform-smoke/gba-result.json", "baselineResultSha256": sha(REPO / "build/platform-smoke/gba-result.json"),
                "baselineTest": "tests/runtime/GbaSmoke.java", "baselineTestSha256": sha(REPO / "tests/runtime/GbaSmoke.java")}
    if large is not None:
        large["baseGeneratorSha256"]=manifest["gbaDiagnosticRom"]["generatorSha256"];large["baselineResultSha256"]=manifest["gbaDiagnosticRom"]["baselineResultSha256"];manifest["largeGbaDiagnosticRom"]=large
        manifest["pceDiagnosticRom"]={"path":str(pce_path),"sha256":pins["pce-rom.sha256"],"bytes":len(pce),"generator":"tools/platform_smoke_software.py","generatorSha256":sha(REPO/"tools/platform_smoke_software.py"),"baselineResult":str(pce_result),"baselineResultSha256":sha(pce_result)}
    if link_media:
        manifest["linkDiagnostics"]={label:{"path":str(path),"sha256":pins[f"link-{label}-rom.sha256"],"bytes":len(content)}for label,(path,content)in link_media.items()}
        manifest["linkDiagnostics"].update(generator="tools/link_diagnostic.py",generatorSha256=sha(REPO/"tools/link_diagnostic.py"),baselineResult=str(baseline),baselineResultSha256=sha(baseline),diagnosticOnlyBootSha256=pins["link-boot.sha256"],bridgeSha256=link_report["bridgeSHA256"],baseSingleCoreSha256=link_report["baseSingleCoreSHA256"])
    if gba_link_media:
        manifest["gbaLinkDiagnostics"]={label:dict(path=str(path),sha256=sha(path),bytes=len(content))for label,(path,content)in gba_link_media.items()}
        manifest["gbaLinkDiagnostics"].update(generator="tools/link_gba_diagnostic.py",generatorSha256=sha(generator),baselines=gba_link_baselines,bridgeSha256=gba_link_core["platforms"]["windows-x86_64"]["librarySha256"],baseSingleCoreSha256=gba_link_core["baseSingleCoreCompatibility"]["librarySha256"])
    if nds_link_media:
        manifest['ndsLinkDiagnostics']={label:dict(path=str(path),sha256=sha(path),bytes=len(content))for label,(path,content)in nds_link_media.items()}
        manifest['ndsLinkDiagnostics'].update(generator='tools/link_ds_diagnostic.py',generatorSha256=sha(REPO/'tools/link_ds_diagnostic.py'),report=str(diagnostic_report),reportSha256=sha(diagnostic_report),baselines=nds_link_baselines,bridgeSha256=nds_link_core['platforms']['windows-x86_64']['librarySha256'],baseSingleCoreSha256=nds_link_core['baseSingleCoreCompatibility']['librarySha256'])
    if media is not None:manifest['mediaDiagnosticRom']=media
    if latency is not None:manifest['latency']=latency
    if expansion:manifest['expansionDiagnostics']={"programs":expansion,"report":str(expansion_diagnostics),"reportSha256":sha(expansion_diagnostics),"sources":original['sources'],"scope":"Original diagnostic-only DS/3DS/Wii programs; no third-party game or BIOS; build provenance is not Minecraft acceptance"}
    if wbfs_evidence is not None:manifest['expansionDiagnostics']['wiiWbfsDiagnostic']=wbfs_evidence
    input_hashes = {str(path.relative_to(REPO)): sha(path) for path in sources.glob("*.java")}
    if n3ds_link_review:
        manifest['n3dsLinkDiagnostics']={label:dict(path=str(path),sha256=sha(path),bytes=len(content))for label,(path,content)in n3ds_link_media.items()}
        manifest['n3dsLinkDiagnostics'].update(source=str(source),sourceSha256=sha(source),baselines=n3ds_link_baselines,bridgeSha256=n3ds_link_core['platforms']['windows-x86_64']['librarySha256'],baseSingleCoreSha256=n3ds_link_core['baseSingleCoreCompatibility']['librarySha256'],queueCheckpoint=False)
    for side, entry in (("server", "ServerQaMod.java"), ("client", "ClientQaMod.java")):
        classes = out / (side + "-classes")
        classes.mkdir()
        args = ["--release", "21", "-encoding", "UTF-8", "-proc:none", "-implicit:none", "-cp", classpath, "-d", str(classes),
                str(sources / "QaOwner.java"), str(sources / "ReplyQueue.java"), str(sources / entry), str(sources / ("ReviewScene.java" if side == "server" else "NativeReview.java")),
                str(sources / ("DockScene.java" if side == "server" else "DockActions.java"))]
        if side == "server":
            args.append(str(sources / "PixelScene.java"))
            args.append(str(sources / "PolishScene.java"))
            args.append(str(sources / "CartridgeScene.java"))
            args.append(str(sources / "MachineScene.java"))
            args.append(str(sources / "CaseScene.java"))
            args.append(str(sources / "UpgradeScene.java"))
            args.append(str(sources / "DockContainerScene.java"))
            args.append(str(sources / "LinkScene.java"))
            args.append(str(sources / "LinkDsScene.java"))
            args.append(str(sources / "Link3dsScene.java"))
            args.append(str(sources / "ExpansionScene.java"))
        else:
            args.append(str(sources / "HeldActions.java"))
            args.append(str(sources / "PolishActions.java"))
            args.append(str(sources / "CartridgeActions.java"))
            args.append(str(sources / "MachineActions.java"))
            args.append(str(sources / "CaseActions.java"))
            args.append(str(sources / "IconActions.java"))
            args.append(str(sources / "IconReviewScreen.java"))
            args.append(str(sources / "UpgradeActions.java"))
            args.append(str(sources / "LinkActions.java"))
            args.append(str(sources / "LinkDsActions.java"))
            args.append(str(sources / "Link3dsActions.java"))
            args.append(str(sources / "MediaMetrics.java"))
            args.append(str(sources / "TransferActions.java"))
            args.append(str(sources / "ExpansionActions.java"))
            args.append(str(sources / "RotationActions.java"))
            args.append(str(sources / "LatencyBridge.java"))
            args.append(str(sources / "RecipeBridge.java"))
            if recipe_review:
                args.append(str(sources / "RecipeActions.java"))
                args.append(str(sources / "RecipeProbePlugin.java"))
            if latency_review:
                args.append(str(sources / "LatencyActions.java"))
                args.append(str(sources / "LatencyTimer.java"))
        argument_file = out / (side + ".args")
        sdk.argfile(argument_file, args)
        command = [str(jdk / "javac.exe"), "-J-Xmx512m", "-J-XX:ActiveProcessorCount=1", "-J-Dfile.encoding=UTF-8", "-J-Duser.language=en", "@" + str(argument_file)]
        compiled = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120, cwd=REPO)
        (out / (side + ".log")).write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
        if compiled.returncode:
            raise RuntimeError(side + " QA compilation failed: " + compiled.stderr[-5000:])
        mod_id = "muxi_console_qa_" + side
        resources = {path.relative_to(classes).as_posix(): path.read_bytes() for path in classes.rglob("*.class")}
        if any(not path.startswith("net/muxigame/consoleqa/") for path in resources):
            raise RuntimeError("QA must never contain product classes")
        if recipe_review:resources['diagnostics/recipe-review.enabled']=b'1'
        resources["console-qa-pins.properties"] = "".join(key + "=" + value + "\n" for key, value in pins.items()).encode("ascii")
        resources["diagnostics/two-port-battery.nes"] = rom.read_bytes()
        resources["diagnostics/original.gb"] = gb_rom.read_bytes()
        resources["diagnostics/original-vi.z64"] = n64
        resources["diagnostics/pong.zip"] = pong
        if gba is not None:
            resources["diagnostics/original-arm.gba"] = gba
        if media is not None:resources['diagnostics/original-two-port-tone.nes']=media_path.read_bytes()
        if latency is not None:
            resources['diagnostics/original-realtime.gba']=latency_rom.read_bytes()
            resources['diagnostics/original-two-port-tone.nes']=latency_nes.read_bytes()
            resources['diagnostics/original-expansion.nds']=latency_nds.read_bytes()
        for row in expansion.values():resources['diagnostics/original-expansion.'+row['suffix']]=Path(row['path']).read_bytes()
        for label,(path,content) in link_media.items():resources[f"diagnostics/original-link-{label}.gb"]=content
        for label,(path,content)in gba_link_media.items():resources[f"diagnostics/original-link-{label}.gba"]=content
        for label,(path,content)in nds_link_media.items():resources[f"diagnostics/original-link-{label}.nds"]=content
        for label,(path,content)in n3ds_link_media.items():resources[f"diagnostics/original-link-{label}.3dsx"]=content
        if link_boot is not None:resources["diagnostics/original-link-boot.bin"]=link_boot
        if pce is not None:resources["diagnostics/original.pce"]=pce
        resources["diagnostics/model-review-plan.json"] = plan
        resources["META-INF/neoforge.mods.toml"] = (f'modLoader="javafml"\nloaderVersion="[4,)"\nlicense="Private test fixture; not a product"\n'
                f'[[mods]]\nmodId="{mod_id}"\nversion="0.1.0"\ndisplayName="Muxi Console {side} QA"\n'
                f'[[dependencies.{mod_id}]]\nmodId="muxi_console"\ntype="required"\nversionRange="[0.1.0,)"\nordering="AFTER"\nside="BOTH"\n').encode("utf-8")
        artifact = out / ("muxi-console-qa-" + side + ".jar")
        with zipfile.ZipFile(artifact, "w", zipfile.ZIP_DEFLATED) as jar:
            for path, body in sorted(resources.items()):
                info = zipfile.ZipInfo(path, (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                jar.writestr(info, body)
            if large is not None:
                info=zipfile.ZipInfo("diagnostics/large-original-arm.gba",(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
                with jar.open(info,"w")as target,Path(large["path"]).open("rb")as source:
                    for block in iter(lambda:source.read(32768),b""):target.write(block)
        manifest["artifacts"][side] = {"path": str(artifact), "sha256": sha(artifact), "modId": mod_id}
    if sha(product) != pins["product.sha256"] or sha(rom) != pins["rom.sha256"] or sha(gb_rom) != pins["gb-rom.sha256"] or sha(n64_rom) != pins["n64-rom.sha256"] or sha(plan_path) != pins["model-plan.sha256"] or any(sha(REPO / path) != digest for path, digest in input_hashes.items()):
        raise RuntimeError("QA inputs changed during compilation")
    if gba is not None and (sha(gba_rom) != pins["gba-rom.sha256"] or sha(REPO / "tools/gba_diagnostic.py") != manifest["gbaDiagnosticRom"]["generatorSha256"] or sha(REPO / "build/platform-smoke/gba-result.json") != manifest["gbaDiagnosticRom"]["baselineResultSha256"] or sha(REPO / "tests/runtime/GbaSmoke.java") != manifest["gbaDiagnosticRom"]["baselineTestSha256"]):
        raise RuntimeError("Original GBA fixture inputs changed during compilation")
    if large is not None and(sha(Path(large["path"]))!=large["sha256"]or sha(REPO/"qa/large_gba.py")!=large["generatorSha256"]or sha(pce_path)!=pins["pce-rom.sha256"]or sha(pce_result)!=manifest["pceDiagnosticRom"]["baselineResultSha256"]or sha(REPO/"tools/platform_smoke_software.py")!=manifest["pceDiagnosticRom"]["generatorSha256"]):raise RuntimeError("Cartridge diagnostic inputs changed during compilation")
    if any(sha(path)!=digest for path,digest in media_inputs.items()):raise RuntimeError('Original media probe inputs changed during QA compilation')
    if any(sha(path)!=digest for path,digest in n3ds_link_inputs.items()):raise RuntimeError('Original 3DS link inputs changed during QA compilation')
    if any(sha(path)!=digest for path,digest in expansion_inputs.items()):raise RuntimeError('Original expansion inputs changed during QA compilation')
    if any(sha(path)!=digest for path,digest in latency_inputs.items()):raise RuntimeError('Original latency proof/source changed during QA compilation')
    if runtime_artifact is not None and sha(runtime)!=runtime_artifact['sha256']or jei_artifact is not None and sha(jei)!=jei_artifact['sha256']:raise RuntimeError('Split runtime/JEI changed during QA compilation')
    if any(sha(p)!=digest for p,digest in gba_link_inputs.items()):raise ValueError("GBA link QA source/baseline changed while compiling")
    if any(sha(p)!=digest for p,digest in nds_link_inputs.items()):raise ValueError('DS link baseline/source changed during QA build')
    manifest["sources"] = input_hashes
    report = out / "qa-build.json"
    report.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"qaBuild": str(report), "artifacts": manifest["artifacts"], "minecraftTested": False}))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--product", type=Path, required=True)
    parser.add_argument("--rom", type=Path, default=REPO / "build/runtime-smoke/two-port-battery.nes")
    parser.add_argument("--gb-rom", type=Path, default=REPO / "build/platform-smoke/original.gb")
    parser.add_argument("--pixel-review", action="store_true")
    parser.add_argument("--cartridge-review",action="store_true")
    parser.add_argument("--model-plan",type=Path)
    parser.add_argument("--link-review",action="store_true")
    parser.add_argument("--gba-link-review",action="store_true")
    parser.add_argument("--nds-link-review",action="store_true")
    parser.add_argument("--n3ds-link-review",action="store_true")
    parser.add_argument('--media-probe',type=Path,help='Exact candidate native audible NES probe.json for media transport QA')
    parser.add_argument('--expansion-diagnostics',type=Path,help='Pinned original no-BIOS DS/3DS/Wii diagnostic build report')
    parser.add_argument('--wii-wbfs-diagnostic',type=Path,help='Explicit source-bound authored WBFS report; keeps default original DOL unchanged')
    parser.add_argument('--large-rom-mib',type=int,choices=(8,32),default=8,help='Only FF padding after the original 64KiB GBA program; 32MiB for slow resumable transfer QA')
    parser.add_argument('--latency-review',action='store_true',help='Only the verified first-party realtime GBA and audible NES regression fixtures')
    parser.add_argument('--latency-probe',type=Path,help='Explicit >=30s paced GBA inputs.json; defaults to the preserved original baseline-03')
    parser.add_argument('--runtime',type=Path,help='Exact class-free client runtime pack; omitted for legacy fat candidates')
    parser.add_argument('--jei',type=Path,help='Exact current clean-source JEI artifact, never a copied integration addon')
    parser.add_argument('--recipe-review',action='store_true',help='Actual optional JEI U/R/search plus authored GB/GBA preservation')
    options = parser.parse_args()
    build(options.name, options.product, options.rom, options.gb_rom, options.pixel_review,options.cartridge_review,options.model_plan,options.link_review,options.media_probe,options.expansion_diagnostics,options.large_rom_mib,options.gba_link_review,options.nds_link_review,options.n3ds_link_review,options.latency_review,options.latency_probe,options.runtime,options.jei,options.recipe_review,options.wii_wbfs_diagnostic)
