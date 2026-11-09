"""Original small voxel hardware for the three new platform slots; no imported meshes or artwork."""
import pixel_hardware as p

PALETTES={
    'nds':('282D33','454B53','69717B','10161C','B6BDC3','537A9B','A5ADB6'),
    'n3ds':('173C44','317D8B','225B68','0C2027','BFCFD0','73C0C6','C9D8D8'),
    'wii':('C3C8C4','F0F1EA','AEB8B3','1C2A32','899DA5','4CA4C5','DCE2DC'),
}
PLANES={
    'nds': [('top',(4.4,9.1,11.6,14.5,6.04),(256,192),False),
            ('bottom',(4.4,2.1,11.6,7.5,5.94),(256,192),True)],
    'n3ds':[('top',(3.25,9.0,12.75,14.7,6.04),(400,240),False),
            ('bottom',(4.5,1.8,11.5,7.05,5.94),(320,240),True)],
}


def palette(platform,ns):
    result=p.palette('generic',ns)
    for name,color in zip(('ink','shell','edge','dark','metal','accent','button'),PALETTES[platform]):
        result[name]=f'{ns}:block/hardware/c_{color.lower()}'
    result['screen']=result['dark'];result['display']=f'{ns}:block/hardware/idle_color'
    result['engraving']=f'{ns}:block/hardware/engraving_{platform}'
    return result


def colors():return {color for row in PALETTES.values() for color in row}


def slot(platform):
    if platform in PLANES:
        return {'width':2.8 if platform=='nds' else 3.0,'base':[8,6.2,8.2],
                'rotation':[0,180,0],'align':'bottom','depth_scale':1,
                'clip':{'axis':'y','keep':'above','value':8.05}}
    return {'width':6.3,'base':[9.0,5.1,7.2],'rotation':[0,90,0],'align':'bottom',
            'depth_scale':1,'clip':{'axis':'z','keep':'below','value':4.55}}


def planes(platform):
    return [{'from':[a[0],a[1],a[4]],'to':[a[2],a[3],a[4]],'facing':'north',
             'surface':name,'native_size':list(size),'touchable':touch}
            for name,a,size,touch in PLANES.get(platform,[])]


def geometry(platform,b,surfaces=None):
    if platform=='wii':
        e=[b([1,0,2.8],[15,.65,13.2],'ink'),b([1.5,.65,3.3],[14.5,1.0,12.7],'edge'),
           b([4.3,1.0,4.5],[11.7,14.9,11.7],'shell'),b([4.65,14.9,4.8],[11.35,15.4,11.4],'shell'),
           b([4.6,1.25,4.35],[11.4,14.7,4.6],'shell'),
           b([8.82,3.25,4.28],[9.22,13.6,4.48],'dark'),
           b([9.22,3.25,4.30],[9.32,13.6,4.48],'accent'),
           b([5.25,12.8,4.23],[6.2,13.65,4.47],'button'),
           b([5.43,11.15,4.24],[6.02,11.72,4.46],'edge'),
           b([5.25,2.1,4.24],[6.35,2.75,4.48],'dark'),
           b([5.55,13.95,4.23],[5.9,14.15,4.48],'led')]
        for i in range(7):e.append(b([6.9+i*.42,15.35,6],[7.08+i*.42,15.48,10.2],'dark'))
        for i in range(4):e.append(b([5.3+i*1.25,2.5,11.68],[6.05+i*1.25,3.2,11.8],'dark'))
        return e
    later=platform=='n3ds'
    e=p.rounded(b,.55 if later else .8,.55,15.45 if later else 15.2,8.35,6.12,8.45,'shell',.55)
    e+=p.rounded(b,1.0 if later else 1.8,8.25,15.0 if later else 14.2,15.45,6.22,8.25,'shell',.45)
    e+=[b([1.1,.75,6.02],[14.9,8.12,6.28],'edge'),
        b([2.4,8.10,6.4],[13.6,8.65,8.36],'edge'),
        b([6.4,8.12,6.2],[9.6,8.60,8.4],'shell')]
    for _,(x0,y0,x1,y1,z),_,_ in PLANES[platform]:
        e+=[b([x0-.22,y0-.22,z+.025],[x1+.22,y1+.22,z+.22],'dark'),
            b([x0,y0,z+.01],[x1,y1,z+.08],'screen',north='display')]
    def key(bit,cx,cy,r=.30,material='button'):
        return p.button(p.control_box(b,surfaces,bit),cx,cy,5.68,r,material,.68)
    cross_y=3.55 if later else 5.1
    cross=p.cross(b,13.55,cross_y,5.65,1.65);e+=cross
    p.directional_surfaces(surfaces,13.55,cross_y,5.65,1.65,
        cap_boxes=[{'from':v['from'],'to':v['to']} for v in cross])
    for bit,x,y in ((8,1.6,4.9),(0,2.55,3.95),(9,2.55,5.85),(1,3.5,4.9)):
        e+=key(bit,x,y)
    for bit,y in ((3,2.05),(2,1.35)):
        e+=p.capsule(p.control_box(b,surfaces,bit),1.8,y,3.25,y+.21,5.82,'button',.45)
    if later:
        pad=p.button(b,13.55,6.5,5.59,.55,'metal',.64);e+=pad
        p.directional_surfaces(surfaces,13.55,6.5,5.59,1.1,
            cap_boxes=[{'from':v['from'],'to':v['to']} for v in pad])
        e+=p.capsule(b,7.1,.77,8.9,.98,5.85,'button',.42)
    for bit,x0,x1 in ((11,.9,4.0),(10,12.0,15.1)):
        cb=p.control_box(b,surfaces,bit,'up');e+=[cb([x0,8.0,6.55],[x1,8.37,8.32],'button')]
    if later:
        for bit,x0,x1 in ((13,3.8,5.25),(12,10.75,12.2)):
            e+=[p.control_box(b,surfaces,bit,'up')([x0,8.12,7.55],[x1,8.48,8.45],'button')]
    for x in ((2.5,13.2) if not later else (1.7,14.1)):
        for i in range(4):e+=[b([x,10.2+i*.68,6.16],[x+.23,10.5+i*.68,6.35],'dark')]
    e+=[b([7.7,14.93,6.13],[8.3,15.22,6.33],'dark'),
        b([14.4,1.1,6.04],[14.66,1.32,6.28],'led'),
        b([5.6,2.2,8.42],[10.4,6.8,8.53],'ink'),
        b([5.8,2.4,8.51],[10.2,6.6,8.57],'shell')]
    return e


def controls(platform,b):
    surfaces=[];geometry(platform,b,surfaces)
    return [{k:v for k,v in surface.items() if not k.startswith('_')} for surface in surfaces]


def regions(platform,b):
    surfaces=[];geometry(platform,b,surfaces);result=[];seen=set()
    for surface in surfaces:
        for cap in surface['_caps']:
            key=(surface['button'],surface['port'],*cap['from'],*cap['to'])
            if key in seen:continue
            seen.add(key);result.append({'button':surface['button'],'port':surface['port'],
                'from':list(cap['from']),'to':list(cap['to'])})
    return result


def media(platform,b):
    if platform=='wii':
        # An independently drawn stepped optical disc with an actual central opening.
        e=[]
        for x0,y0,x1,y1 in ((5.3,3.4,10.7,5.2),(3.4,5.2,6.9,10.8),(9.1,5.2,12.6,10.8),(5.3,10.8,10.7,12.6),
                             (6.9,5.2,9.1,6.9),(6.9,9.1,9.1,10.8)):
            e.append(b([x0,y0,7.82],[x1,y1,8.18],'metal'))
        e+=[b([5.55,10.9,7.76],[10.45,12.3,7.92],'white',north='label')]
        return e
    later=platform=='n3ds';x0,x1=(5.05,10.95) if later else (5.25,10.75)
    e=[b([x0,4.7,7.45],[x1,10.85,8.55],'shell'),b([x0+.25,10.85,7.45],[x1-.25,11.3,8.55],'shell'),
       b([x0+.35,6.1,7.36],[x1-.35,10.2,7.52],'white',north='label')]
    if later:e.append(b([x1,10.4,7.45],[x1+.6,11.3,8.55],'shell'))
    for i in range(8):e.append(b([x0+.42+i*.56,4.3,7.63],[x0+.70+i*.56,5.15,8.45],'brass'))
    e.append(b([x0+.4,10.45,7.37],[x1-.4,10.64,7.55],'edge'))
    return e
