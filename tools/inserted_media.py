"""Original attachment coordinates in authored 1/16-block device space."""

# Width keeps the real media proportions; base anchors its rotated lower edge.
# Only the part beyond the mouth plane is visible. The rest remains inside the slot.
SLOTS={
 'nes':(7.6,(8,3.8,5.9),(90,0,180),('z','below',2.05),.75),
 'snes':(5.9,(8,4.6,8.75),(0,0,0),('y','above',6.02),1),
 'gb':(5.5,(8,9.4,8.12),(0,180,0),('y','above',12.4),1),
 'gbc':(5.3,(8,9.4,7.89),(0,180,0),('y','above',12.5),1),
 'gba':(6.0,(8,8.1,7.83),(0,180,0),('y','above',11.15),1),
 'md':(6.0,(8,3.4,9.1),(0,0,0),('y','above',4.76),1),
 'n64':(6.2,(8,4.35,9.45),(0,0,0),('y','above',5.64),1),
 'sms':(4.6,(8,3.3,8.5),(0,0,0),('y','above',4.54),1),
 'gg':(4.6,(8,7.25,8.31),(0,180,0),('y','above',11.45),1),
 'sg1000':(4.6,(8,3.4,7.5),(0,0,0),('y','above',4.82),1),
 'pce':(6.4,(8,1.8,3.8),(90,0,180),('z','below',3.0),1),
 'a2600':(4.0,(8,3.55,7.0),(0,0,0),('y','above',4.84),1),
 'a7800':(4.35,(8,3.3,6.75),(0,0,0),('y','above',4.8),1),
 'lynx':(4.8,(8,8.7,8.25),(0,180,0),('y','above',11.15),1),
 'ngp':(4.4,(8,8.9,8.07),(0,180,0),('y','above',12.05),1),
 'ngpc':(4.5,(8,8.9,8.02),(0,180,0),('y','above',12.05),1),
 'ws':(4.3,(8,8.85,7.72),(0,180,0),('y','above',11.55),1),
 'wsc':(4.4,(8,9.0,7.65),(0,180,0),('y','above',11.6),1),
 'vb':(4.4,(8,10.2,11.45),(0,180,0),('y','above',12.7),1),
 'fds':(6.4,(8,1.95,4.75),(90,0,180),('z','below',2.1),.55),
 'arcade':(3.3,(8,2.9,14.0),(0,180,0),('z','above',13.85),1),
 'generic_desktop':(5.4,(8,3.4,9.4),(0,0,0),('y','above',5.24),1),
 'generic':(5.6,(8,13.3,8.35),(0,180,0),('y','above',11.85),1),
}


def slot(platform):
 width,base,rotation,clip,depth=SLOTS[platform]
 return {'width':width,'base':list(base),'rotation':list(rotation),
         'align':'top' if platform=='generic' else 'bottom','depth_scale':depth,
         'clip':{'axis':clip[0],'keep':clip[1],'value':clip[2]}}
