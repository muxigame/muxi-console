"""Retirement keeps exact ownership, complete file inventory and active-run boundaries."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import prepare_profile as subject


class RetirementTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.base=Path(self.temp.name).resolve()
        self.environment=self.base/'test-environment'
        self.releases=self.environment/'release-test'
        self.root=self.releases/'canonical-test'
        self.root.mkdir(parents=True)
        self.profile=self.root/'validation-profile.json'
        self.profile.write_text('{}',encoding='utf-8')
        self.digest=subject.release.hashes(self.profile)['sha256']
        (self.root/'preparation-owner.json').write_text(json.dumps(dict(workspace=str(subject.WORKSPACE),name=self.root.name,productionWrites=False)),encoding='utf-8')
        (self.root/'preparation-result.json').write_text('{}',encoding='utf-8')
        self.receipt=self.root/'link-limit-copy-receipt.json'
        self.receipt.write_text(json.dumps(dict(explicitlyEnabled=False,files=[],copiedBytes=0,securityChecksChanged=False,sharedSourceModified=False)),encoding='utf-8')
        self.patches=[patch.object(subject.release,'RELEASES',self.releases),patch.object(subject.release,'ENVIRONMENT',self.environment),patch.object(subject.release,'load_profile',return_value={'_root':self.root,'clientFiles':[],'serverFiles':[]})]
        for item in self.patches:item.start()
        self.addCleanup(self.temp.cleanup)
        for item in self.patches:self.addCleanup(item.stop)

    def test_owned_inactive_canonical_empty_receipt_is_retired(self):
        result=subject.retire_profile(self.profile,self.digest)
        self.assertEqual(result['sha256'],self.digest)
        self.assertFalse(self.root.exists())

    def test_copy_receipt_is_not_a_general_unknown_file_exception(self):
        for change in ({'files':[{'foreign':'file'}]},{'copiedBytes':1},{'securityChecksChanged':True},{'extra':'unknown'}):
            value=dict(explicitlyEnabled=False,files=[],copiedBytes=0,securityChecksChanged=False,sharedSourceModified=False)
            value.update(change);self.receipt.write_text(json.dumps(value),encoding='utf-8')
            with self.subTest(change=change),self.assertRaises(ValueError):subject.retire_profile(self.profile,self.digest)
            self.assertTrue(self.root.exists())

    def test_unknown_sidecar_and_foreign_owner_are_still_rejected(self):
        unknown=self.root/'private-unlisted.txt';unknown.write_text('keep',encoding='utf-8')
        with self.assertRaises(ValueError):subject.retire_profile(self.profile,self.digest)
        self.assertTrue(unknown.exists());unknown.unlink()
        owner=self.root/'preparation-owner.json';owner.write_text(json.dumps(dict(workspace='foreign',name=self.root.name,productionWrites=False)),encoding='utf-8')
        with self.assertRaises(ValueError):subject.retire_profile(self.profile,self.digest)
        self.assertTrue(self.root.exists())

    def test_referencing_unclosed_run_prevents_retirement(self):
        run=self.environment/'client-2/runs/active-test';run.mkdir(parents=True)
        (run/'local-mc-owner.json').write_text(json.dumps(dict(runId='test',releaseProfile={'path':str(self.profile),'sha256':self.digest})),encoding='utf-8')
        with self.assertRaises(FileNotFoundError):subject.retire_profile(self.profile,self.digest)
        self.assertTrue(self.root.exists())


if __name__=='__main__':unittest.main()
