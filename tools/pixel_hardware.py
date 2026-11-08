"""Authored low-resolution hardware geometry; no downloaded meshes or textures.

Front faces are north. Local +X is the viewer's left, so D-pads live at
the larger X end and action buttons at the smaller X end.
"""

import inserted_media as attachments

PALETTES = {
    'nes':('48494D','D6D5CF','A7A9A9','22242A','C7C8C6','B6333B','393B40'),
    'snes':('87888E','D3D1CF','ADB0B1','33363E','B8BAB9','7770AD','59565E'),
    'gb':('AFAE9F','D1D0BE','A5A698','383B42','B1B1A8','8C3458','252A2C'),
    'gbc':('3F355F','7760A0','5A4684','232532','9B8CB4','60507D','20232B'),
    'gba':('34346C','6666AE','474583','252636','B9BAC4','A1A2B7','32333D'),
    'md':('181A1E','303238','24272D','0B0D11','9B9D9F','A62935','22252B'),
    'n64':('292B2E','373A3E','202226','101215','A0A4A6','44494E','22272A'),
    'sms':('23252A','34363A','202226','0E1116','96999C','B63C43','41464B'),
    'gg':('25272D','34383F','202329','101419','A4A9AC','A44755','202329'),
    'sg1000':('8E9190','D9D9CB','B7BAAF','252A2E','AEB2AA','B84E43','484E50'),
    'pce':('9B9D96','E3E0D2','C7C6BB','292E31','BEC0B7','B13F43','575D5D'),
    'a2600':('25211E','38332E','1C1A19','0B0B0C','A0A09B','C18A4E','3E3F3C'),
    'a7800':('22262A','3D4348','AFB1AE','101318','B6B9B7','A34045','646B6D'),
    'lynx':('252A2E','373D41','20272B','101519','ABB0B1','AFAA9B','555E62'),
    'ngp':('878D8E','B5BBBA','9AA3A3','242A2E','CDD0C9','525A5E','30383C'),
    'ngpc':('253B65','456A99','31527D','182334','9DAAB8','303C50','1E2D43'),
    'ws':('929899','D6D7CF','AEB7B5','293337','BFC7C1','5C686B','3B4649'),
    'wsc':('637C8C','AFC5CC','849FAE','22313A','D5DDDA','3E5665','354B5A'),
    'vb':('2C2529','B72D3C','7C1F2C','151419','A7AAAB','D8444C','343439'),
    'fds':('8E2735','DCD8BF','B9B5A6','24272A','A9A8A0','B92E41','5A5555'),
    'arcade':('20212A','303440','171B22','0A0D13','979DA4','BC344B','4D6068'),
    'generic':('383E48','C8CDD0','8C969E','232933','AAB4BA','5F7299','434E59'),
}

# Hand-authored plastic/powder-coat colors, independent of the frozen TV palette.
EXTRA_COLORS = {'red':'BB354B','blue':'3C76AF','yellow':'D6B949','green':'54A67E',
                'white':'E2E2DB','rubber':'171C21','led':'88B477','brass':'C4A86E',
                'wood':'855D3D','wood_light':'A4774C','lcd':'748763'}


def palette(platform, ns='muxi_console'):
    values=PALETTES[platform]
    roles=('ink','shell','edge','dark','metal','accent','button')
    out={role:f'{ns}:block/hardware/c_{color.lower()}' for role,color in zip(roles,values)}
    out.update({name:f'{ns}:block/hardware/c_{color.lower()}' for name,color in EXTRA_COLORS.items()})
    out.update({'amber':out['red'],'connector':out['brass'],'light':out['white'],
                'screen':out['lcd'] if platform in ('gb','ngp','ws') else out['dark'],
                'lens':f'{ns}:block/hardware/c_411d2b',
                'display':f'{ns}:block/hardware/idle_mono' if platform in ('gb','ngp','ws') else f'{ns}:block/hardware/idle_color',
                'label':f'{ns}:block/hardware/serial',
                'wood':f'{ns}:block/hardware/wood_grain',
                'marquee':f'{ns}:block/hardware/marquee',
                'engraving':f'{ns}:block/hardware/engraving_{platform}'})
    return out


def colors():
    return sorted({color for values in PALETTES.values() for color in values}|set(EXTRA_COLORS.values())|{'411D2B'})


def rounded(b,x0,y0,x1,y1,z0,z1,material='shell',corner=.6):
    return [b([x0+corner,y0,z0],[x1-corner,y1,z1],material),
            b([x0,y0+corner,z0],[x0+corner,y1-corner,z1],material),
            b([x1-corner,y0+corner,z0],[x1,y1-corner,z1],material)]


def button(b,cx,cy,z=5.3,r=.55,material='accent',depth=1.2):
    return [b([cx-r*.65,cy-r,z],[cx+r*.65,cy+r,z+depth],material),
            b([cx-r,cy-r*.65,z],[cx+r,cy+r*.65,z+depth],material)]


def cross(b,cx,cy,z=5.3,size=2.35):
    r=size/2;arm=size/6
    return [b([cx-r,cy-arm,z],[cx+r,cy+arm,z+1.2],'button'),
            b([cx-arm,cy-r,z],[cx+arm,cy+r,z+1.2],'button'),
            b([cx-.21,cy-.21,z-.035],[cx+.21,cy+.21,z+.02],'ink')]


def four_keys(b,cx,cy,z=5.3,size=2.1):
    """Four separate directional keys, as opposed to a connected D-pad."""
    radius=size/2;w=size*.29;offset=radius-w/2
    return [b([cx+dx-w/2,cy+dy-w/2,z],[cx+dx+w/2,cy+dy+w/2,z+1.2],'button')
            for dx,dy in ((0,offset),(0,-offset),(offset,0),(-offset,0))]


def control_box(b,surfaces,bit,facing='north',port=0):
    """Record visible rectangles from the very same authored cap geometry."""
    def emit(start,end,material,**faces):
        element=b(start,end,material,**faces)
        if surfaces is not None:
            lo=list(start);hi=list(end)
            if facing=='north':hi[2]=lo[2]
            elif facing=='up':lo[1]=hi[1]
            else:raise ValueError(facing)
            surfaces.append({'button':bit,'from':lo,'to':hi,'facing':facing,'port':port,
                             '_caps':[{'from':list(start),'to':list(end)}]})
        return element
    return emit


def capsule(b,x0,y0,x1,y1,z,material='button',depth=.65):
    # Three steps retain a low-resolution pill silhouette without square ends.
    return rounded(b,x0,y0,x1,y1,z,z+depth,material,min((y1-y0)*.35,(x1-x0)*.2))


def directional_surfaces(surfaces,cx,cy,z,size,bits=(4,5,6,7),port=0,cap_boxes=None):
    if surfaces is None:return
    r=size/2;arm=size/6
    if cap_boxes is None:
        cap_boxes=cross(lambda start,end,material,**faces:{'from':start,'to':end},cx,cy,z,size)
    # Legacy front patches remain available; complete physical caps also carry all six faces.
    # Only each arm's outer tip lights; the engraved centre remains visible.
    rectangles=((cx-arm,cy+arm,cx+arm,cy+r),
                (cx-arm,cy-r,cx+arm,cy-arm),
                (cx+arm,cy-arm,cx+r,cy+arm),
                (cx-r,cy-arm,cx-arm,cy+arm))
    for bit,(x0,y0,x1,y1) in zip(bits,rectangles):
        surfaces.append({'button':bit,'from':[x0,y0,z],'to':[x1,y1,z],'facing':'north','port':port,
                         '_caps':[{'from':list(e['from']),'to':list(e['to'])} for e in cap_boxes]})


def slim_depth(elements,pivot,scale=.76):
    # Keep the dynamic screen exactly in place; remove depth from the rear.
    for element in elements:
        for point in ('from','to'):
            element[point][2]=round(pivot+(element[point][2]-pivot)*scale,6)
        if '_caps' in element:slim_depth(element['_caps'],pivot,scale)
    return elements


def screen(b,plane,pad=.42):
    x0,y0,x1,y1,z=plane
    return [b([x0-pad,y0-pad,z+.05],[x1+pad,y1+pad,z+.85],'dark'),
            b([x0,y0,z+.02],[x1,y1,z+.08],'screen',north='display')]


def sockets(b,count,x0,x1,y,z):
    out=[];step=(x1-x0)/count
    for i in range(count):
        x=x0+step*(i+.5)
        out+=rounded(b,x-.8,y,x+.8,y+1.1,z,z+.22,'metal',.2)
        out+=rounded(b,x-.6,y+.2,x+.6,y+.9,z-.04,z+.05,'dark',.15)
    return out


def deck_feet(b,x0=1.7,x1=14.3,z0=3,z1=13):
    return [b([x,0,z],[x+1,.4,z+1],'rubber') for x in (x0,x1-1) for z in (z0,z1-1)]


def top_disc(b,cx,cz,r,y0,y1,material):
    return [b([cx-r*.65,y0,cz-r],[cx+r*.65,y1,cz+r],material),
            b([cx-r,y0,cz-r*.65],[cx+r,y1,cz+r*.65],material)]


def top_vents(b,x0,x1,y,z0,count=8,length=2.2):
    step=(x1-x0)/count
    return [b([x0+i*step,y,z0],[x0+i*step+.18,y+.12,z0+length],'dark') for i in range(count)]


def handheld(platform,b,planes,surfaces=None):
    p=planes[platform]
    def action(bit,cx,cy,z,r=.4,material='accent',depth=1.2):
        return button(control_box(b,surfaces,bit),cx,cy,z,r,material,depth)
    def direction(cx,cy,z,size):
        directional_surfaces(surfaces,cx,cy,z,size)
        return cross(b,cx,cy,z,size)
    def key(bit,x0,y0,x1,y1,z,material='button'):
        return capsule(control_box(b,surfaces,bit),x0,y0,x1,y1,z,material)
    def separate(cx,cy,z,size,bits):
        radius=size/2;w=size*.29;offset=radius-w/2
        return [control_box(b,surfaces,bit)([cx+dx-w/2,cy+dy-w/2,z],
                    [cx+dx+w/2,cy+dy+w/2,z+1.2],'button')
                for bit,(dx,dy) in zip(bits,((0,offset),(0,-offset),(offset,0),(-offset,0)))]
    if platform=='gb':
        e=[b([3.8,1.7,6.15],[12.2,14.9,8.8],'shell'),
           b([4.3,1.05,6.15],[12.2,1.7,8.8],'shell'),
           b([5.0,.8,6.15],[11.8,1.05,8.8],'shell'),
           b([4.1,14.9,6.15],[11.9,15.2,8.8],'shell'),
           b([3.8,1.7,6.45],[12.2,14.9,6.58],'edge'),
           b([4.35,7.9,5.98],[11.65,14.48,6.28],'edge'),
           b([5.1,2.0,8.78],[10.9,9.0,8.94],'ink'),
           b([5.25,2.16,8.92],[10.75,8.85,9.01],'shell')]
        e+=direction(10.2,5.7,5.62,2.15)+action(8,4.85,6.0,5.56,.4)+action(0,6.4,5.25,5.56,.4)
        for bit,x in ((3,7.0),(2,8.65)):
            e+=key(bit,x-.44,3.43,x+.44,3.69,5.88)
        e+=[b([4.55+i*.55,1.9+i*.13,5.99],[4.74+i*.55,2.8+i*.13,6.16],'dark') for i in range(5)]
        e+=[b([11.75,11.8,5.99],[12.0,12.1,6.17],'red'),b([5.8,15.2,6.6],[7.5,15.37,7.5],'button')]
    elif platform=='gbc':
        e=rounded(b,3.95,.9,12.05,14.8,6.02,8.52,'shell',.65)
        e+=rounded(b,4.3,14.5,11.7,15.25,6.2,8.35,'shell',.2)
        e+=[b([4.2,1.6,6.35],[11.8,14.4,6.48],'edge'),b([4.62,7.94,5.74],[11.38,14.25,6.15],'dark'),
            b([5.15,2.3,8.5],[10.85,10.7,8.67],'ink'),b([5.33,2.45,8.65],[10.67,10.5,8.72],'shell')]
        e+=direction(10.25,5.6,5.52,2.0)+action(8,4.8,5.95,5.46,.38,'button')+action(0,6.25,5.25,5.46,.38,'button')
        e+=key(3,7.4,3.4,8.3,3.65,5.78)+key(2,8.85,3.4,9.75,3.65,5.78)
        e+=[b([4.6+i*.43,1.95,5.91],[4.78+i*.43,2.67,6.15],'dark') for i in range(5)]
        e+=[b([11.55,11.6,5.72],[11.82,11.9,6.1],'red')]
    elif platform=='gba':
        e=rounded(b,.3,4.05,15.7,10.95,6.05,8.55,'shell',.75)
        e+=rounded(b,1.0,3.65,15.0,11.5,6.2,8.35,'shell',.8)
        e+=[b([.8,5.0,6.0],[3.4,9.85,6.27],'edge'),b([12.6,5.0,6.0],[15.2,9.85,6.27],'edge'),
            control_box(b,surfaces,11,'up')([1.55,10.9,6.4],[4.2,11.55,8.1],'metal'),
            control_box(b,surfaces,10,'up')([11.8,10.9,6.4],[14.45,11.55,8.1],'metal'),
            b([4.0,4.66,5.56],[12.0,10.14,6.18],'dark'),b([5.1,4.0,8.53],[10.9,10.6,8.7],'ink'),
            b([5.3,4.2,8.68],[10.7,10.4,8.75],'shell')]
        e+=direction(13.55,7.45,5.4,2.1)+action(8,1.55,7.85,5.4,.4,'metal')+action(0,2.8,6.6,5.4,.4,'metal')
        e+=key(3,5.65,4.12,6.55,4.37,5.82)+key(2,7.3,4.12,8.2,4.37,5.82)
        e+=[b([1.05+i*.45,5.25,5.82],[1.23+i*.45,5.95,6.15],'dark') for i in range(5)]
        e+=[b([14.65,9.45,5.94],[14.92,9.72,6.15],'green')]
    elif platform in ('gg','lynx'):
        lynx=platform=='lynx';x0,x1=(.1,15.9) if lynx else (.3,15.7)
        e=rounded(b,x0,3.75,x1,11.85,6.14,9.15,'shell',.85)
        e+=rounded(b,x0+.35,4.1,x1-.35,11.5,6.0,6.45,'ink',.7)
        e+=[b([4.55,4.0,9.13],[11.45,11.55,9.32],'ink')]
        e+=direction(13.7 if lynx else 13.0,7.4,5.5 if lynx else 5.66,2.05)
        e+=action(8,1.4 if lynx else 1.8,7.9,5.45 if lynx else 5.58,.4,'button')
        e+=action(0,2.6 if lynx else 3.1,6.5,5.45 if lynx else 5.58,.4,'button')
        if lynx:
            # Repeated A/B groups permit turning the device for left-handed use.
            e+=action(8,1.4,10.5,5.45,.4,'button')+action(0,2.6,9.3,5.45,.4,'button')
            e += [*key(10,.65,7.3,1.22,7.53,5.76,'metal'),
                  *key(3,.65,7.82,1.22,8.05,5.76,'metal'),
                  *key(11,.65,8.34,1.22,8.57,5.76,'metal')]
            e += [b([13.1+i*.45,4.75,5.92],[13.3+i*.45,5.4,6.2],'dark') for i in range(5)]
        else:
            e += key(3,1.65,9.86,2.7,10.16,5.8,'blue')
            e += [b([1.25+i*.42,4.65,5.95],[1.43+i*.42,5.35,6.18],'dark') for i in range(6)]
        e += [b([11.95,10.5,5.92],[12.2,10.8,6.16],'red')]
    elif platform in ('ngp','ngpc'):
        color=platform=='ngpc'
        e=rounded(b,.35,3.4,15.65,12.4,6.13,8.8,'shell',.75)
        e+=rounded(b,.9,3.15,15.1,12.55,6.32,8.55,'edge',.55)
        e+=[b([4.2,4.97,5.96 if not color else 5.76],[11.65,12.05,6.4],'dark'),
            b([4.85,3.55,8.78],[11.15,11.95,8.97],'ink')]
        # Recessed octagonal click stick, not an oversized cross.
        e+=rounded(b,11.8,5.65,14.35,8.4,5.48,6.23,'dark',.45)
        e+=button(b,13.08,7.03,5.32,.62,'button',.6)
        directional_surfaces(surfaces,13.08,7.03,5.32,1.0,cap_boxes=button(
            lambda start,end,material,**faces:{'from':start,'to':end},13.08,7.03,5.32,.62,'button',.6))
        e+=action(8,1.85,7.9,5.52,.4,'button')+action(0,3.1,6.5,5.52,.4,'button')
        e += key(3,2.18,10.66,3.12,10.93,6.0)
        e += [b([2.0+i*.45,4.15,5.97],[2.17+i*.45,4.9,6.2],'dark') for i in range(5)]
        if color:e += [b([6.0,12.4,6.45],[10.0,12.75,8.15],'ink'),b([14.3,10.3,5.98],[14.6,10.6,6.2],'green')]
        else:e += [b([6.2,12.55,6.8],[9.8,12.7,8.0],'button')]
    elif platform in ('ws','wsc'):
        color=platform=='wsc'
        e=rounded(b,.45,3.6,15.55,11.65,6.06,8.4,'shell',.65)
        e+=rounded(b,1.0,3.25,15.0,12.0,6.26,8.2,'edge',.55)
        e += [b([2.9,4.6,5.9 if not color else 5.65],[11.02,10.3,6.36],'dark'),
              b([4.7,4.0,8.38],[11.3,11.2,8.56],'ink')]
        e += separate(13.6,8.9,5.53,1.85,(13,12,10,11))+separate(13.6,5.95,5.53,1.85,(4,5,6,7))
        e += action(8,1.25,7.7,5.48,.36,'button')+action(0,2.4,6.5,5.48,.36,'button')
        e += key(3,6.4,4.06,7.3,4.3,5.84)
        e += [b([.9+i*.42,4.4,5.93],[1.07+i*.42,5.1,6.2],'dark') for i in range(5)]
        if color:e += [b([4.5,11.65,6.4],[11.0,12.3,8.0],'shell'),
                       *capsule(b,8.15,4.06,9.05,4.3,5.84)]  # Sound control is not the core's rotate binding.
        else:e += [b([11.6,10.7,5.95],[12.5,11.25,6.27],'button')]
    else:raise ValueError(platform)
    result=slim_depth(e+screen(b,p),p[4])
    if surfaces is not None:slim_depth(surfaces,p[4])
    return result


def _geometry(platform,b,planes,arcade_planes):
    if platform in planes:return handheld(platform,b,planes)
    if platform=='arcade':return _arcade(b,False,arcade_planes)
    f=deck_feet(b)
    if platform=='nes':
        e=f+[b([1,.4,2],[15,3.6,14],'ink'),b([1,3.6,2],[15,5.4,14],'shell'),
             b([1,5.4,9.1],[15,6.1,14],'edge'),b([3.0,3.5,1.84],[12.9,5.25,2.16],'dark'),
             b([3.1,5.05,1.78],[12.8,5.15,4.2],'shell'),b([3.6,5.1,1.72],[12.3,5.3,1.93],'edge'),
             b([10.65,1.2,1.73],[12.0,2.12,2.16],'button'),b([12.3,1.2,1.73],[13.65,2.12,2.16],'button'),
             b([13.95,1.55,1.71],[14.2,1.9,2.1],'red')]
        e+=sockets(b,2,2.5,7.3,1.1,1.8)+top_vents(b,2.2,13.8,6.1,10.0,12,2.8)
        return e
    if platform=='snes':
        e=f+rounded(b,1,.4,15,3.1,2.6,13.4,'ink',.65)
        e += [b([1.4,3.1,3.05],[14.6,4.0,13.0],'shell'),b([2.15,4,3.65],[13.85,4.65,12.5],'shell'),
              b([3.6,4.65,6.0],[12.4,5.45,11.9],'edge'),b([4.2,5.45,7.0],[11.8,5.9,11.1],'shell'),
              b([4.65,5.88,8.3],[11.35,6.1,9.2],'dark'),b([6.0,4.65,4.1],[10.0,5.1,5.8],'button'),
              b([11.85,4.6,5.1],[13.1,5.1,7.5],'accent'),b([2.9,4.6,5.1],[4.15,5.1,7.5],'accent')]
        return e+sockets(b,2,2.2,13.8,1.25,2.4)+top_vents(b,3.2,12.8,4.65,11.8,10,.6)
    if platform=='md':
        e=f+[b([1.4,.4,2.2],[14.6,3.4,13.8],'ink'),b([.7,.7,4],[15.3,3.3,12],'ink'),
             b([1.9,3.4,3],[14.1,3.8,13.2],'shell'),b([2.9,3.8,4.2],[13.1,4.15,12.7],'ink')]
        e+=top_disc(b,8,9.1,4.1,4.15,4.4,'edge')+top_disc(b,8,9.1,3.5,4.4,4.6,'shell')
        e += [b([4.5,4.6,8.6],[11.5,4.82,9.65],'dark'),b([11.9,3.8,4.8],[13.25,4.2,6.1],'accent'),
              b([2.9,3.8,4.9],[4.2,4.2,6.0],'button'),b([10.6,3.78,3.5],[12.2,3.95,3.75],'white')]
        return e+sockets(b,2,2.5,13.5,1.0,2.05)+top_vents(b,3,13,3.8,11.0,10,1.7)
    if platform=='n64':
        e=deck_feet(b,z0=2.2)+rounded(b,.8,.4,15.2,3.9,2.3,13.7,'ink',.85)
        e += [b([1.7,3.9,3.5],[14.3,4.35,13.0],'shell'),b([3.1,4.35,6.4],[12.9,4.8,12.5],'shell'),
              b([4.25,4.8,8],[11.75,5.5,11.8],'edge'),b([4.8,5.5,8.9],[11.2,5.72,10.0],'dark'),
              b([11.7,4.35,4.2],[13.5,4.7,6.25],'button'),b([2.7,4.35,4.3],[4.2,4.7,6.0],'button'),
              b([6.3,4.35,3.7],[9.7,4.55,5.75],'ink'),b([6.6,4.55,4.02],[9.4,4.64,5.42],'button')]
        return e+sockets(b,4,1.65,14.35,1.25,2.16)+top_vents(b,3,13,4.35,11.6,12,.7)
    if platform=='sms':
        e=f+[b([1,.4,2],[15,2.7,14],'ink'),b([1.3,2.7,4.0],[14.7,3.6,13.8],'shell'),
             b([1.8,3.6,6.6],[14.2,4.4,13.5],'ink'),b([1.7,4.4,10.4],[14.3,4.95,13.5],'accent'),
             b([4.9,4.4,8.0],[11.1,4.62,9.05],'dark'),b([3.1,1.6,1.86],[12.9,2.08,2.1],'dark'),
             b([11.65,3.6,4.5],[13.05,4.04,5.75],'button'),b([2.95,3.6,4.5],[4.35,4.04,5.75],'button')]
        return e+sockets(b,2,5.5,13.9,.55,1.83)+top_vents(b,3,13,4.95,11.5,12,1.3)
    if platform=='sg1000':
        e=f+[b([1,.4,2],[15,2.9,14],'ink'),b([1.15,2.9,2.15],[14.85,4.2,13.85],'shell'),
             b([2,4.2,9.8],[14,5.0,13.4],'edge'),b([4.1,4.2,6.0],[11.9,4.7,9.9],'shell'),
             b([4.7,4.68,7.0],[11.3,4.9,8.05],'dark'),b([1.8,4.2,3],[14.2,4.4,3.55],'accent'),
             b([11.9,4.2,4.0],[13.35,4.65,5.35],'accent'),b([3.2,4.2,4.2],[5.1,4.5,5.1],'button')]
        return e+sockets(b,2,3.0,11.6,1.2,1.86)+top_vents(b,2.4,13.6,5.0,10.6,10,2.0)
    if platform=='pce':
        e=deck_feet(b,3.2,12.8,3.2,12.8)+rounded(b,3,.4,13,3.1,3,13,'ink',.35)
        e += [b([3.18,3.1,3.18],[12.82,3.8,12.82],'shell'),b([3.45,3.8,10.4],[12.55,4.05,12.55],'edge'),
              b([4.15,1.8,2.85],[11.85,2.35,3.12],'dark'),b([4.55,2.0,2.8],[11.45,2.2,3.0],'metal'),
              b([11.1,3.8,4.25],[12.2,4.18,5.55],'accent'),b([3.8,3.8,4.2],[5.0,4.1,5.3],'button')]
        return e+sockets(b,1,6.3,9.7,.55,2.82)+top_vents(b,4.0,12.0,4.05,10.8,12,1.3)
    if platform=='a2600':
        e=f+[b([.6,.4,2],[15.4,3.0,14],'ink'),b([.6,.9,1.85],[15.4,2.8,2.2],'wood'),
             b([1.0,3.0,3.0],[15,3.7,13.7],'shell'),b([1.3,3.7,7.2],[14.7,4.8,13.5],'dark'),
             b([5.0,3.7,5.5],[11,4.7,8.6],'ink'),b([5.5,4.7,6.4],[10.5,4.9,7.6],'dark')]
        e += [b([1.6+i*.74,4.8,8.6],[1.85+i*.74,5.1,13.1],'ink') for i in range(18)]
        for x in (1.8,3.6,5.4,10.2,12.0,13.8):
            e += [b([x-.5,3.7,3.7],[x+.5,3.92,5.2],'dark'),b([x-.18,3.92,4.0],[x+.18,4.95,4.5],'metal')]
        return e
    if platform=='a7800':
        e=f+[b([1,.4,2.2],[15,2.2,13.8],'ink'),b([1.6,2.2,3.1],[14.4,2.9,13.6],'edge'),
             b([2.3,2.9,5.2],[13.7,3.7,13.3],'shell'),b([3.0,3.7,7.8],[13.0,4.25,12.9],'ink'),
             b([5.0,3.7,5.4],[11,4.65,8.3],'ink'),b([5.55,4.65,6.25],[10.45,4.86,7.3],'dark')]
        for x in (3.8,6.0,10.0,12.2):e += [b([x-.65,2.9,3.7],[x+.65,3.26,4.75],'button')]
        return e+sockets(b,2,3.7,12.3,.8,2.08)+top_vents(b,3.6,12.4,4.25,9,12,3.2)
    if platform=='fds':
        e=f+[b([1,.4,2],[15,3.65,14],'accent'),b([1.45,3.65,2.45],[14.55,4.05,13.55],'shell'),
             b([2.0,4.05,4.4],[14.0,6.65,12.9],'shell'),b([2.45,6.65,4.85],[13.55,7.15,12.45],'shell'),
             b([4.6,7.15,8.1],[11.4,7.4,9.1],'dark'),b([2.45,4.7,4.2],[5.2,6.3,4.5],'accent'),
             b([10.8,4.7,4.2],[13.55,6.3,4.5],'accent'),b([3.0,1.9,1.82],[12.0,2.75,2.18],'dark'),
             b([3.3,2.1,1.76],[11.7,2.55,1.94],'ink'),b([12.6,1.5,1.77],[13.8,2.9,2.17],'button'),
             b([1.65,2.0,1.76],[1.95,2.3,2.12],'green')]
        return e+top_vents(b,3.5,12.5,7.15,10.1,10,1.8)
    if platform=='vb':
        e=rounded(b,1.8,8.4,14.2,12.55,5.8,12.2,'shell',.65)
        e += [b([2.6,12.55,6.4],[13.4,13.2,11.6],'shell'),b([3.2,8.1,6.0],[12.8,8.5,11.8],'ink'),
              b([1.35,9.15,6.8],[1.9,11.8,11.2],'ink'),b([14.1,9.15,6.8],[14.65,11.8,11.2],'ink')]
        for x in (2.8,9.2):
            e+=rounded(b,x,8.7,x+4,12.0,4.6,6.4,'dark',.45)
            e+=[b([x+.6,9.1,4.52],[x+3.4,11.5,4.7],'lens')]
        e += [b([6.65,10.1,5.05],[9.35,11.85,6.3],'dark')]
        e += [b([7.3,5.5,8.0],[8.7,8.4,9.2],'metal'),b([6.1,4.4,7.1],[9.9,5.6,10.5],'button'),
              b([5.3,2.3,5.6],[6.6,4.5,8.7],'metal'),b([9.4,2.3,5.6],[10.7,4.5,8.7],'metal'),
              b([4.4,.6,3.7],[5.7,2.4,7.2],'ink'),b([10.3,.6,3.7],[11.6,2.4,7.2],'ink'),
              b([7.3,.6,10.0],[8.7,4.4,13.5],'ink')]
        return e
    raise ValueError(platform)


def _arcade(b,double,planes,surfaces=None):
    x0,x1=(.4,15.6) if double else (3,13);y=4.9 if double else 6.3;plane=planes['double' if double else 'single']
    e=[b([x0,0,4],[x1,.7,14],'dark'),b([x0+.35,.7,4.3],[x1-.35,y,13.65],'ink'),
       b([x0,1.0,4],[x0+.72,15.75,14],'accent'),b([x1-.72,1,4],[x1,15.75,14],'accent'),
       b([x0+.72,y,5.05],[x1-.72,15.4,13.65],'shell'),b([x0+.72,14.5,3.75],[x1-.72,15.6,5.2],'dark',north='marquee'),
       b([x0-.15,y,1.8],[x1+.15,y+.35,5.35],'dark'),b([x0+.1,y+.35,1.95],[x1-.1,y+.75,4.8],'ink'),
       b([x0+.35,y+.75,2.15],[x1-.35,y+.9,4.5],'button'),
       b([7.15,2.2,4.04],[8.85,4.5,4.31],'metal'),b([7.5,3.6,3.99],[8.5,3.83,4.2],'dark'),
       b([7.5,2.7,3.97],[8.5,3.18,4.18],'red')]
    # Simple original side chevrons replace repeated ornamental plaques.
    for side in (x0-.025,x1-.1):
        for i in range(4):e+=[b([side,5.0+i*1.15,8.0+i*.45],[side+.125,5.45+i*1.15,11.0+i*.45],'blue')]
    for index,cx in enumerate((11.8,4.2) if double else (8,)):
        material='blue' if index==0 else 'red'
        e += [b([cx+.7,y+.9,2.55],[cx+1.35,y+1.5,3.2],'dark')]
        e+=button(b,cx+1.02,y+1.5,2.45,.52,material,.75)
        directional_surfaces(surfaces,cx+1.02,y+1.5,2.45,.8,port=index,cap_boxes=button(
            lambda start,end,material,**faces:{'from':start,'to':end},cx+1.02,y+1.5,2.45,.52,material,.75))
        for row in range(2):
            for col in range(3):
                xx=cx-1.6+col*.7;zz=2.5+row*.85
                bit=((0,8,1),(9,10,11))[row][col]
                e+=top_disc(control_box(b,surfaces,bit,'up',index),xx,zz,.25,y+.9,y+1.1,('red','yellow','green')[col])
        for bit,xx in ((3,cx+.35),(2,cx+1.3)):
            cb=control_box(b,surfaces,bit,'up',index)
            e+=[cb([xx, y+.9,3.85],[xx+.7,y+1.07,4.09],'metal'),
                cb([xx+.08,y+.9,3.8],[xx+.62,y+1.07,4.14],'metal')]
    return e+screen(b,plane)


def media(platform,index,b):
    sizes={'nes':(9.1,12.1,1.6),'snes':(11.5,8.3,2.1),'gb':(7.4,8.2,1.1),'gbc':(7.5,8.7,1.25),
           'gba':(8.1,4.9,1.3),'md':(10.0,8.4,2.2),'n64':(11.5,7.7,2.2),'sms':(7.9,10.5,1.8),
           'gg':(7.2,8.0,1.6),'sg1000':(8.2,10.8,2.0),'pce':(10.3,7.4,.48),'a2600':(8.6,9.7,2.7),
           'a7800':(8.8,9.2,2.4),'lynx':(8.7,6.2,.65),'ngp':(7.0,6.6,1.2),'ngpc':(7.3,6.9,1.25),
           'ws':(7.4,5.8,1.0),'wsc':(7.7,6.2,1.05),'vb':(8.7,6.7,1.35),'arcade':(10.0,7.4,1.05)}
    if platform=='fds':
        return [b([3,2.6,7.4],[13,13.2,8.4],'yellow'),b([3.4,8.1,7.22],[12.6,12.7,7.5],'white',north='label'),
                b([6.1,4.8,7.16],[9.9,8.0,7.48],'dark'),b([7.0,5.65,7.1],[9.0,7.15,7.4],'metal'),
                b([10.8,3.2,7.18],[12.2,7.7,7.46],'dark'),b([3.3,3.6,7.22],[3.95,4.3,7.5],'white')]
    w,h,d=sizes[platform];x0,x1=8-w/2,8+w/2;y0,y1=8-h/2,8+h/2;z0,z1=8-d/2,8+d/2
    material='shell' if platform in ('nes','snes','gb','pce') else 'ink'
    e=[b([x0,y0,z0],[x1,y1-.45,z1],material),b([x0+.4,y1-.45,z0],[x1-.4,y1,z1],material)]
    e+=[b([x0+.65,y0+1.6,z0-.08],[x1-.65,y1-.85,z0+.03],'white',north='label')]
    if platform in ('pce','lynx'):
        e += [b([x0+.38+i*(w-.76)/16,y0-.3,z0],[x0+.58+i*(w-.76)/16,y0+.45,z1],'brass') for i in range(16)]
    else:
        e += [b([x0+.8,y0-.45,z0+.15],[x1-.8,y0+.28,z1-.15],'brass')]
    if platform=='nes':
        e += [b([x0+.3,y0+.7+i*.52,z0-.15],[x0+1.2,y0+.88+i*.52,z0+.1],'edge') for i in range(14)]
        e += [b([x1-1.2,y0+.7+i*.52,z0-.15],[x1-.3,y0+.88+i*.52,z0+.1],'edge') for i in range(14)]
    elif platform in ('gb','gbc','gba','gg'):
        e += [b([x0+.25,y1-.8,z0-.12],[x1-.25,y1-.6,z0+.08],'edge')]
        e += [b([x0+.3+i*.58,y1-.5,z0-.12],[x0+.5+i*.58,y1-.05,z0+.08],'button') for i in range(3+index%4)]
    elif platform=='n64':
        e += [b([x0+.9,y1,z0+.15],[x1-.9,y1+.35,z1-.15],'edge'),b([x0+.4,y0+.7,z0-.1],[x0+1.1,y1-.3,z0+.08],'edge')]
    else:e += [b([x0+.2,y0+.8+i*.5,z0-.12],[x0+.6,y0+1.0+i*.5,z0+.08],'edge') for i in range(2+index%4)]
    return e


def generic(kind,b,surfaces=None):
    if kind=='handheld':
        e=rounded(b,0,3,16,12.2,6.3,8.5,'shell',.75)
        e += [b([4.0,4.8,6.02],[12.0,11.1,6.4],'dark'),
              control_box(b,surfaces,11,'up')([.8,11.6,6.5],[3.3,12.4,8.2],'edge'),
              control_box(b,surfaces,10,'up')([12.7,11.6,6.5],[15.2,12.4,8.2],'edge')]
        e += cross(b,13.7,7.3,5.835,2.05)
        directional_surfaces(surfaces,13.7,7.3,5.835,2.05)
        e += button(control_box(b,surfaces,8),1.5,7.9,5.8,.38)+button(control_box(b,surfaces,0),2.8,6.7,5.8,.38)
        e += capsule(control_box(b,surfaces,3),6.1,4.05,7.0,4.3,6.0)
        e += capsule(control_box(b,surfaces,2),8.3,4.05,9.2,4.3,6.0)
        return slot_details('generic',b,e+screen(b,(4.3,5.2,11.7,10.75,5.98)),{'generic':None})
    if kind=='desktop':
        e=deck_feet(b)+[b([1,.4,2],[15,3.5,14],'ink'),b([1.5,3.5,2.5],[14.5,4.2,13.5],'shell'),
             b([3.8,4.2,7.4],[12.2,5.1,11.7],'edge'),b([4.5,5.1,8.9],[11.5,5.3,9.9],'dark'),
             b([11.9,4.2,3.8],[13.2,4.65,5.2],'button'),b([2.8,4.2,3.8],[4.1,4.65,5.2],'button')]
        return e+sockets(b,2,3,13,1.2,1.87)+top_vents(b,3,13,4.2,11.7,12,1.1)
    if kind=='dock':
        return [b([1,0,2],[15,.6,14],'dark'),b([1.35,.6,2.35],[14.65,1.75,13.65],'ink'),
            b([1.8,1.75,2.8],[14.2,2.35,13.2],'shell'),b([3.5,2.35,6.4],[12.5,2.6,10.2],'dark'),
            b([3.5,2.6,6.4],[4.3,3,10.2],'edge'),b([11.7,2.6,6.4],[12.5,3,10.2],'edge'),
            b([5.8,2.6,8.2],[10.2,2.85,8.8],'brass'),b([12.5,.95,2.28],[12.8,1.25,2.48],'green'),
            b([2.8,.8,2.28],[5.0,1.45,2.48],'dark'),b([7.0,.8,2.28],[9.2,1.45,2.48],'dark')]
    if kind=='writer':
        return deck_feet(b)+[b([1,.4,2],[15,4.9,14],'ink'),b([1.4,4.9,2.4],[14.6,5.7,13.6],'shell'),
            b([3.1,5.7,6.4],[12.9,6.7,12.0],'edge'),b([3.8,6.7,7.8],[12.2,7.0,9.0],'dark'),
            b([2.2,1.7,1.86],[9.6,4.1,2.1],'dark'),b([2.6,2.1,1.8],[9.2,3.7,1.96],'screen',north='display'),
            b([11.1,2.2,1.8],[12.6,3.6,2.2],'button'),b([13.2,3.5,1.8],[13.5,3.8,2.1],'green')]
    if kind=='controller':
        e=rounded(b,1.0,4.5,15.0,10.5,6.25,8.7,'shell',1.0)
        e += [b([1.5,3.25,6.5],[4.6,4.8,8.7],'ink'),b([11.4,3.25,6.5],[14.5,4.8,8.7],'ink'),
              control_box(b,surfaces,11,'up')([2.5,10.3,6.5],[5.4,11.0,8.3],'edge'),
              control_box(b,surfaces,10,'up')([10.6,10.3,6.5],[13.5,11.0,8.3],'edge')]
        e += capsule(control_box(b,surfaces,3),6.4,6.75,7.3,7.0,5.95)
        e += capsule(control_box(b,surfaces,2),8.6,6.75,9.5,7.0,5.95)
        e += cross(b,12.3,7.6,5.7,2.2)
        directional_surfaces(surfaces,12.3,7.6,5.7,2.2)
        for bit,x,y,m in ((8,3.1,8.0,'red'),(0,4.3,6.8,'yellow'),(9,4.3,9.2,'blue'),(1,5.5,8.0,'green')):
            e+=button(control_box(b,surfaces,bit),x,y,5.65,.36,m)
        return e
    if kind=='cartridge':return media('gb',3,b)
    raise ValueError(kind)


def _controls(platform,b,planes,arcade_planes,double=False):
    """Exact authored visible cap planes, using public RetroPad button IDs."""
    surfaces=[]
    if platform in planes:handheld(platform,b,planes,surfaces)
    elif platform=='generic':generic('handheld',b,surfaces)
    elif platform=='controller':generic('controller',b,surfaces)
    elif platform=='arcade':arcade(b,double,arcade_planes,surfaces)
    return surfaces


def control_surfaces(platform,b,planes,arcade_planes,double=False):
    return [{k:v for k,v in surface.items() if not k.startswith('_')}
            for surface in _controls(platform,b,planes,arcade_planes,double)]


def button_regions(platform,b,planes,arcade_planes,double=False):
    regions=[];seen=set()
    for surface in _controls(platform,b,planes,arcade_planes,double):
        for cap in surface['_caps']:
            key=(surface['button'],surface['port'],*cap['from'],*cap['to'])
            if key in seen:continue
            seen.add(key)
            regions.append({'button':surface['button'],'port':surface['port'],
                            'from':list(cap['from']),'to':list(cap['to'])})
    return regions


def _back_screw(b,cx,cy,z):
    return [b([cx-.12,cy-.12,z-.03],[cx+.12,cy+.12,z+.02],'button'),
            b([cx-.075,cy-.025,z+.018],[cx+.075,cy+.025,z+.027],'metal')]


def fine_details(platform,b,e,planes):
    # Small manufacturing details are geometry, kept clear of live windows and caps.
    if platform in planes:
        plane=planes[platform];x0,y0,x1,y1,z=plane
        e += [b([(x0+x1)/2-.95,y0-.35,z+.029],[(x0+x1)/2+.95,y0-.20,z+.08],
                'dark',north='engraving')]
        bounds=[min(v['from'][i] for v in e) for i in range(3)]+[max(v['to'][i] for v in e) for i in range(3)]
        cx=(bounds[0]+bounds[3])/2;cy=(bounds[1]+bounds[4])/2
        rear=max(v['to'][2] for v in e if v['from'][0]<=cx<=v['to'][0] and v['from'][1]<=cy<=v['to'][1])
        e += [b([cx-1.35,cy-.28,rear-.025],[cx+1.35,cy+.28,rear+.025],'edge',south='engraving')]
        for x,y in ((bounds[0]+.9,bounds[1]+1.2),(bounds[3]-.9,bounds[4]-1.2)):
            surfaces=[v['to'][2] for v in e if v['from'][0]<=x<=v['to'][0] and v['from'][1]<=y<=v['to'][1]]
            if surfaces:e+=_back_screw(b,x,y,max(surfaces))
        return e
    if platform=='vb':
        return e+[b([7.0,12.72,6.37],[9.0,12.94,6.43],'shell',north='engraving'),
                  *_back_screw(b,3.3,11.7,12.2),*_back_screw(b,12.7,11.7,12.2)]
    if platform=='arcade':
        # Small cabinet fasteners sit on side rails, not on the lit LCD or controls.
        for x in (min(v['from'][0] for v in e)+.45,max(v['to'][0] for v in e)-.45):
            for y in (9.0,14.0):
                e += [b([x-.11,y-.11,3.97],[x+.11,y+.11,4.05],'metal')]
        return e
    x0=min(v['from'][0] for v in e);x1=max(v['to'][0] for v in e)
    z0=min(v['from'][2] for v in e);z1=max(v['to'][2] for v in e)
    for x in (x0+1.2,x1-1.2):
        for z in (z0+1.3,z1-1.3):
            heights=[v['to'][1] for v in e if v['from'][0]<=x<=v['to'][0] and v['from'][2]<=z<=v['to'][2]]
            if heights:
                y=max(heights)
                e += [b([x-.12,y-.02,z-.12],[x+.12,y+.05,z+.12],'button'),
                      b([x-.075,y+.048,z-.02],[x+.075,y+.063,z+.02],'metal')]
    # A modest original assembly label and slotted rear vent replace decorative front plaques.
    primary=max(e,key=lambda v:(v['to'][0]-v['from'][0])*(v['to'][1]-v['from'][1])*(v['to'][2]-v['from'][2]))
    lo,hi=primary['from'],primary['to'];cx=(lo[0]+hi[0])/2;cy=(lo[1]+hi[1])/2
    e += [b([cx-1.3,cy-.23,hi[2]-.02],[cx+1.3,cy+.23,hi[2]+.025],'dark',south='engraving')]
    for i in range(5):
        x=lo[0]+.65+i*.24
        e += [b([x,cy-.33,hi[2]-.02],[x+.09,cy+.33,hi[2]+.02],'dark')]
    return e


def slot_details(platform,b,e,planes):
    if platform in planes or platform=='vb':
        slot=attachments.slot(platform);width=slot['width'];cx,_,cz=slot['base'];y=slot['clip']['value']
        back=max(element['to'][2] for element in e)
        rear=min(back,cz+.18);front=rear-.58
        for x in (cx-width/2-.14,cx+width/2+.02):
            e += [b([x,y-.42,front],[x+.12,y+.07,rear],'dark')]
        e += [b([cx-width/2-.14,y-.16,rear-.11],[cx+width/2+.14,y+.015,rear],'dark')]
    elif platform=='arcade':
        e += [b([6.12,2.7,13.6],[6.32,5.65,13.97],'metal'),b([9.68,2.7,13.6],[9.88,5.65,13.97],'metal'),
              b([6.12,2.7,13.6],[9.88,2.91,13.97],'dark')]
    return e


def geometry(platform,b,planes,arcade_planes):
    return slot_details(platform,b,fine_details(platform,b,_geometry(platform,b,planes,arcade_planes),planes),planes)


def monitor_palette(ns='muxi_console'):
    out=palette('generic',ns)
    for role,color in {'ink':'202226','shell':'303238','edge':'48494D','dark':'101215',
                       'metal':'646B6D','button':'22272A','screen':'101215'}.items():
        out[role]=f'{ns}:block/hardware/c_{color.lower()}'
    out['display']=f'{ns}:block/standby'
    return out


def monitor(b,single=False):
    if single:
        e=rounded(b,.4,5.7,15.6,15.4,6.2,8.4,'ink',.3)
        e += [b([1.1,6.65,6.16],[14.9,14.4125,6.27],'screen',north='display'),
              b([7,.8,8.25],[9,6.2,10.1],'shell'),b([6.3,5.2,8.2],[9.7,8.1,9.2],'shell'),
              b([4.3,0,2.8],[11.7,.65,13.2],'ink'),b([4.55,.65,3.05],[11.45,1,12.95],'shell'),
              b([13.5,6.02,6.15],[13.75,6.18,6.3],'led')]
        for x in (2.0,13.8):e+=_back_screw(b,x,14.0,8.4)
        for x in range(6):e += [b([4.9+x*.95,12.4,8.38],[5.3+x*.95,12.52,8.46],'dark')]
        e += [b([9.8,7.2,8.38],[12.7,8.4,8.65],'button'),b([10.1,7.48,8.63],[11.25,7.87,8.7],'dark'),b([11.7,7.48,8.63],[12.4,7.87,8.7],'dark')]
        return e
    e=rounded(b,-15.5,12,15.5,31,6.2,8.4,'ink',.45)
    e += [b([-14.6,13.15,6.16],[14.6,29.575,6.27],'screen',north='display'),
          b([-1.4,1,8.25],[1.4,12.8,10.2],'shell'),b([-3,11.5,8.2],[3,16,10.0],'shell'),
          b([-7,0,2.8],[7,.8,13.2],'ink'),b([-6.7,.8,3.1],[6.7,1.4,12.9],'shell'),
          b([12.9,12.4,6.15],[13.25,12.58,6.3],'led'),
          b([-15.15,12.35,6.18],[15.15,12.49,6.36],'shell')]
    for x in (-13.7,13.7):
        for y in (14.1,29.2):e+=_back_screw(b,x,y,8.4)
    for x in range(12):e += [b([-5.5+x*.95,25.9,8.38],[-5.12+x*.95,26.06,8.5],'dark')]
    e += [b([5.3,15.0,8.38],[11.4,17.2,8.65],'button'),b([5.8,15.5,8.63],[8.2,16.1,8.74],'dark'),
          b([8.8,15.5,8.63],[10.6,16.1,8.74],'dark'),b([-.8,3.8,10.18],[.8,4.6,10.45],'dark')]
    return e


def arcade(b,double,planes,surfaces=None):
    return slot_details('arcade',b,fine_details('arcade',b,_arcade(b,double,planes,surfaces),{}),{})
