"""API 34+ shell input helper; no APK installation or app callback simulation."""
import hashlib
import os
from pathlib import Path
import select
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent


class GestureDriver:
    def __init__(self, device, evidence):
        self.device, self.evidence = device, evidence
        self.process = self.log = self.remote = None
        self.active = False
        self.point = (0, 0)

    def start(self):
        assert int(self.device.adb('shell','getprop','ro.build.version.sdk')) >= 34
        source=ROOT/'tools/gesture-driver/GestureDriver.java'
        digest=hashlib.sha256(source.read_bytes()).hexdigest()
        out=ROOT/'build/evidence/gesture-helper'/digest;out.mkdir(parents=True,exist_ok=True)
        jar=out/'gesture.jar'
        self.log=(self.evidence/'gesture-helper.log').open('w')
        if not jar.exists():
            sdk=Path(os.environ.get('ANDROID_HOME',Path.home()/'Library/Android/sdk'))
            bt=sorted((sdk/'build-tools').glob('*'),key=lambda p:tuple(int(x) for x in p.name.split('.') if x.isdigit()))[-1]
            classes=out/'classes';classes.mkdir(exist_ok=True);dex=out/'dex';dex.mkdir(exist_ok=True)
            platform=sdk/'platforms/android-36/android.jar'
            subprocess.run(['javac','-source','8','-target','8','-classpath',str(platform),'-d',str(classes),str(source)],stdout=self.log,stderr=subprocess.STDOUT,check=True,timeout=90)
            subprocess.run([str(bt/'d8'),'--lib',str(platform),'--min-api','34','--output',str(dex),*map(str,classes.rglob('*.class'))],stdout=self.log,stderr=subprocess.STDOUT,check=True,timeout=90)
            with zipfile.ZipFile(jar,'w') as z:z.write(dex/'classes.dex','classes.dex')
        self.remote='/data/local/tmp/compose-hotreload-gesture-'+digest[:16]+'.jar'
        subprocess.run(['adb','-s',self.device.serial,'push',str(jar),self.remote],stdout=self.log,stderr=subprocess.STDOUT,check=True,timeout=30)
        self.process=subprocess.Popen(['adb','-s',self.device.serial,'shell','CLASSPATH='+self.remote,'app_process','/system/bin','dev.compose.hotreload.tools.GestureDriver'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.log,text=True,bufsize=1)
        assert self.read()=='READY','Gesture helper failed to start'

    def read(self):
        assert select.select([self.process.stdout],[],[],15)[0], 'Gesture helper response timeout'
        return self.process.stdout.readline().strip()

    def send(self, action, x, y):
        if action=='CANCEL' and not self.active:return
        self.process.stdin.write(f'{action} {x} {y}\n');self.process.stdin.flush()
        assert self.read()=='OK','System input injection failed'
        self.active=action not in ('UP','CANCEL');self.point=(x,y)

    def close(self):
        try:
            if self.process and self.process.poll() is None:
                self.send('CANCEL',*self.point)
                self.process.stdin.write('QUIT\n');self.process.stdin.flush()
                self.process.wait(timeout=10)
        finally:
            if self.process and self.process.poll() is None:self.process.terminate();self.process.wait(timeout=10)
            if self.remote:self.device.adb('shell','rm','-f',self.remote)
            if self.log:self.log.close()
