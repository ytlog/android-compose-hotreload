"""Actual raster/layout, Nav3/system-back and content-transition acceptance."""
import json
import struct
import subprocess
import time
import zlib
from framework_ui import UiFeatureCases, APP
from gesture_driver import GestureDriver


def png_pixels(data, max_y):
    """Decode Android screencap RGB/RGBA PNGs without optional host packages."""
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    offset = 8; packed = bytearray()
    while offset < len(data):
        length = struct.unpack('>I', data[offset:offset+4])[0]
        kind = data[offset+4:offset+8]; body = data[offset+8:offset+8+length]
        if kind == b'IHDR':
            width,height,depth,color,compression,filtering,interlace = struct.unpack('>IIBBBBB',body)
            assert depth == 8 and color in (2,6) and compression == filtering == interlace == 0
        elif kind == b'IDAT': packed.extend(body)
        offset += length+12
    bpp = 4 if color == 6 else 3; stride = width*bpp
    raw = zlib.decompress(packed); assert len(raw) == (stride+1)*height
    rows=[]; previous=bytearray(stride)
    for y in range(min(height, max_y+1)):
        start=y*(stride+1); mode=raw[start]; row=bytearray(raw[start+1:start+1+stride])
        assert mode in range(5)
        if mode == 2:
            row = bytearray((a+b)&255 for a,b in zip(row,previous))
        elif mode != 0:
            for x in range(stride):
                a=row[x-bpp] if x>=bpp else 0; b=previous[x]; c=previous[x-bpp] if x>=bpp else 0
                if mode==1: predictor=a
                elif mode==3: predictor=(a+b)//2
                else:
                    p=a+b-c; da,db,dc=abs(p-a),abs(p-b),abs(p-c)
                    predictor=a if da<=db and da<=dc else b if db<=dc else c
                row[x]=(row[x]+predictor)&255
        rows.append(row);previous=row
    def pixel(x,y):
        assert 0<=x<width and 0<=y<height
        return tuple(rows[y][x*bpp:x*bpp+3])
    return width,height,pixel


class VisualCases(UiFeatureCases):
    def drawing_observation(self, name, colors):
        self.d.tap_text('Inspect drawing')
        texts=self.r.snapshot();values=list(map(int,self.get(texts,'Draw report: ').split(':')))
        x,y,w,h,attaches,detaches,caches,frames,px,py,pw,ph=values
        assert w>0 and h>0 and attaches==1 and detaches==0 and caches>=1 and frames>=1
        data=subprocess.check_output(['adb','-s',self.r.args.serial,'exec-out','screencap','-p'])
        (self.r.case/(name+'.png')).write_bytes(data)
        _,_,pixel=png_pixels(data,max(y+h//2,py+ph//2))
        samples=[pixel(x+w//4,y+h//2),pixel(x+w*7//8,y+h//2)]
        palette=[pixel(px+pw*(2*i+1)//8,py+ph//2) for i in range(4)]
        expected=[palette[[(255,0,0),(0,0,255),(255,255,0),(0,255,0)].index(c)] for c in colors]
        assert len(set(palette))==4, 'Reference palette is not rendered distinctly'
        assert samples==expected, 'Actual rendered pixels differ from same-profile references: '+repr(samples)
        result={'bounds':[x,y,w,h],'colors':samples,'referencePalette':palette,'attaches':attaches,'detaches':detaches,'cacheBuilds':caches,'frames':frames}
        (self.r.case/(name+'.json')).write_text(json.dumps(result,indent=2))
        return values

    def custom_drawing(self):
        self.r.new_case('drawing-actual-pixels-layout-and-node')
        self.enter('Drawing'); self.d.tap_text('Redraw')
        before=self.drawing_observation('baseline',[(255,0,0),(255,255,0)])
        texts=self.r.snapshot();anchor='Draw anchor: '+self.get(texts,'Draw anchor: ');node='Draw node: '+self.get(texts,'Draw node: ')
        self.r.passed('drawing-actual-pixels-layout-and-node')
        self.edit('drawing-retained-cache-callback', 'DrawLogic.kt','Color.Red','Color.Blue')
        self.wait(anchor,node,'Draw draft: edited-draw')
        self.d.tap_text('Redraw')
        current=self.drawing_observation('canvas-new-body',[(0,0,255),(255,255,0)])
        assert current[2:4]==before[2:4] and current[6]==before[6], 'Existing cache unexpectedly rebuilt'
        self.r.passed('drawing-retained-cache-callback',reload=True)
        self.edit('drawing-retained-modifier-node', 'DrawLogic.kt','Color.Yellow','Color.Green')
        self.d.tap_text('Redraw');self.wait(anchor,node)
        current=self.drawing_observation('node-new-body',[(0,0,255),(0,255,0)])
        assert current[6]==before[6]
        self.r.passed('drawing-retained-modifier-node',reload=True)
        self.edit('drawing-retained-measure-policy', 'DrawLogic.kt','Int = 120','Int = 160')
        self.d.tap_text('Remeasure');self.wait(anchor,node,'Draw draft: edited-draw')
        current=self.drawing_observation('new-layout-size',[(0,0,255),(0,255,0)])
        assert abs(current[2]/before[2]-4/3)<.01 and current[3]==before[3]
        assert current[6]>before[6], 'Size-dependent draw cache was not rebuilt'
        self.r.passed('drawing-retained-measure-policy',reload=True)
        self.r.new_case('drawing-home-gc-and-detach')
        self.resume_original();self.d.tap_text('GC: ');self.wait(anchor,node,'Draw draft: edited-draw')
        self.drawing_observation('resumed',[(0,0,255),(0,255,0)])
        self.leave();self.wait('Draw lifetime: 1:1:1:1')
        self.r.passed('drawing-home-gc-and-detach')

    def back_touch(self, action, x):
        self.gesture.send(action,x,1000)

    def start_back(self):
        self.back_touch('DOWN',1)
        for x in [40,100,200,350,500]:self.back_touch('MOVE',x);time.sleep(.05)
        self.d.wait_for(lambda ts: any(t.startswith('Nav progress: ') and float(t.split(': ')[1])>0 for t in ts),'real system predictive back progress')

    def navigation_extensions(self):
        original=self.d.adb('shell','settings','get','global','enable_back_animation').strip()
        self.gesture=GestureDriver(self.d,self.r.evidence)
        try:
            self.d.adb('shell','settings','put','global','enable_back_animation','1')
            self.gesture.start()
            self.navigation_extension_checks()
        finally:
            try:self.gesture.close()
            finally:
                if original=='null':self.d.adb('shell','settings','delete','global','enable_back_animation')
                else:self.d.adb('shell','settings','put','global','enable_back_animation',original)
                restored=self.d.adb('shell','settings','get','global','enable_back_animation').strip()==original
                (self.r.evidence/'predictive-setting-restored.json').write_text(json.dumps({'original':original,'restored':restored}))
                assert restored,'Predictive back setting not restored'

    def navigation_extension_checks(self):
        partial=getattr(self.r.args,'nav_phase','all')=='back'
        label='nav-v1' if partial else 'nav-v2'
        self.r.new_case('nav3-back-prerequisite' if partial else 'nav3-original-entry-state')
        self.enter('Nav extensions');self.d.tap_text('Open nav 42');self.d.tap_text('Seed nav 42')
        self.wait('Nav stack: home,42','Nav links: 0','nav-v1:42')
        texts=self.r.snapshot();anchor='Nav extension: '+self.get(texts,'Nav extension: ')
        entry42='Nav page 42: '+self.get(texts,'Nav page 42: ');live42='Nav live 42: '+self.get(texts,'Nav live 42: ')
        if not partial:
            self.r.passed('nav3-original-entry-state')
            self.edit('nav3-live-entry-business-edit','NavExtensionLogic.kt','nav-v1:','nav-v2:')
            self.wait(anchor,entry42,live42,'nav-v2:42')
            self.r.passed('nav3-live-entry-business-edit',reload=True)
            self.r.new_case('nav3-real-deep-link-new-intent')
        self.d.adb('shell','am','start','-W','-a','android.intent.action.VIEW','-d','hotreload-fixture://nav/item/43','-p',APP)
        self.wait(anchor,'Nav stack: home,42,43','Nav links: 1',label+':43')
        self.d.tap_text('Seed nav 43')
        texts=self.r.snapshot();entry43='Nav page 43: '+self.get(texts,'Nav page 43: ');live43='Nav live 43: '+self.get(texts,'Nav live 43: ')
        if not partial:self.r.passed('nav3-real-deep-link-new-intent')
        self.r.new_case('nav3-predictive-back-held-prerequisite')
        try:
            self.start_back();self.wait('Nav back: 1:0:0:back-v1')
            self.edit('nav3-edit-held-system-back-and-cancel','NavExtensionLogic.kt','back-v1','back-v2')
            self.back_touch('MOVE',650);self.wait(anchor,entry43,live43,'Nav back: 1:0:0:back-v2')
            for x in [400,200,40,1]:self.back_touch('MOVE',x);time.sleep(.08)
            self.back_touch('UP',1)
        finally:
            self.back_touch('CANCEL',1)
        self.wait(anchor,entry43,live43,'Nav stack: home,42,43','Nav back: 1:1:0:back-v2')
        self.r.passed('nav3-edit-held-system-back-and-cancel',reload=True)
        self.r.new_case('nav3-system-back-commit-restores-saved-entry')
        try:
            self.start_back();self.back_touch('MOVE',1200);self.back_touch('UP',1200)
        finally:self.back_touch('CANCEL',1)
        self.wait(anchor,entry42,'Nav stack: home,42','Nav back: 2:1:1:back-v2')
        self.r.passed('nav3-system-back-commit-restores-saved-entry')
        self.r.new_case('nav3-next-link-and-original-parent')
        self.d.adb('shell','am','start','-W','-a','android.intent.action.VIEW','-d','hotreload-fixture://nav/item/44','-p',APP)
        self.wait(anchor,'Nav stack: home,42,44','Nav links: 2',label+':44')
        self.d.tap_text('Close nav fixture');self.wait('Draft list','Root: '+self.root,'Nav extension lifetime: 1:1')
        self.r.passed('nav3-next-link-and-original-parent')

    def content_transitions(self):
        self.content_transition_checks()

    def content_transition_checks(self):
        partial=getattr(self.r.args,'content_phase','all')=='settle'
        self.r.new_case('content-settle-prerequisite' if partial else 'content-original-and-incoming-slots')
        self.enter('Content motion');self.d.tap_text('Click content 0')
        self.d.tap_text('Click visible');self.d.tap_text('Switch content')
        self.wait('Content transition: 0:1:true','Content lifetime: 2:0')
        self.d.tap_text('Click content 1')
        texts=self.r.snapshot();anchor='Content anchor: '+self.get(texts,'Content anchor: ')
        old='Content child 0: '+self.get(texts,'Content child 0: ');new='Content child 1: '+self.get(texts,'Content child 1: ')
        visible='Visible child: '+self.get(texts,'Visible child: ')
        assert old.endswith(':1') and new.endswith(':1') and visible.endswith(':1')
        if not partial:
            self.r.passed('content-original-and-incoming-slots')
            self.edit('content-render-during-crossfade','ContentLogic.kt','content-v1:','content-v2:')
            self.wait(anchor,old,new,visible,'Content draft: edited-content','Content rendered 0: content-v2:0','Content rendered 1: content-v2:1','Content transition: 0:1:true')
            self.r.passed('content-render-during-crossfade',reload=True)
            self.edit('content-original-child-callback-during-transition','ContentScreen.kt','clicks += 1','clicks += 3')
            self.d.tap_text('Click content 1');new=new.rsplit(':',1)[0]+':4'
            self.wait(anchor,old,new,visible,'Content lifetime: 2:0')
            self.r.passed('content-original-child-callback-during-transition',reload=True)
        self.r.new_case('content-transition-finishes-and-disposes-once')
        # Do not assume changing an active animation spec or a system setting ends it.
        # Observe the real transition completing on its original animation clock.
        expected=(anchor,new,'Content transition: 1:1:false','Content lifetime: 2:1')
        self.r.wait(lambda: all(t in self.r.snapshot() for t in expected),
                    'natural content transition completion',timeout=660)
        self.check(*expected)
        assert not any(t.startswith('Content child 0:') for t in self.r.snapshot())
        self.r.passed('content-transition-finishes-and-disposes-once')
        self.r.new_case('visibility-exit-keeps-original-child')
        self.d.tap_text('Hide content');self.wait(visible,'Visibility state: true:false:false','Visibility lifetime: 1:0')
        self.r.passed('visibility-exit-keeps-original-child')
        self.edit('visibility-edit-exit-and-cancel','ContentLogic.kt','visible-v1','visible-v2')
        self.wait(anchor,visible,'Visible rendered: visible-v2','Visibility lifetime: 1:0')
        self.d.tap_text('Cancel hide');self.wait(anchor,visible,'Visibility state: true:true:true','Visibility lifetime: 1:0')
        self.r.passed('visibility-edit-exit-and-cancel',reload=True)
        self.r.new_case('visibility-completed-exit-disposes-once')
        self.d.tap_text('Settle hide');self.d.tap_text('Hide content')
        self.wait(anchor,new,'Visibility state: false:false:true','Visibility lifetime: 1:1')
        assert not any(t.startswith('Visible child:') for t in self.r.snapshot())
        self.r.passed('visibility-completed-exit-disposes-once')
        self.r.new_case('content-resume-and-page-disposal')
        self.resume_original();self.d.tap_text('GC: ')
        self.wait(anchor,new,'Content draft: edited-content','Content lifetime: 2:1','Visibility lifetime: 1:1')
        self.leave();self.wait('Content page lifetime: 1:1','Content cleanup: 2:2:1:1')
        self.r.passed('content-resume-and-page-disposal')
