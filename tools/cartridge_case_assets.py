"""Original closed cartridge organiser: one thin hard-shell box, no borrowed assets."""

PALETTE={"case_shell":"435D62","case_edge":"263D43","case_lid":"526C71",
         "case_seal":"172D33","case_latch":"DDD1B5","case_mark":"8B9E98"}


def geometry(box):
    def plate(x0,z0,x1,z1,y0,y1,material,corner=.3):
        return [box([x0+corner,y0,z0],[x1-corner,y1,z1],material),
                box([x0,y0,z0+corner],[x0+corner,y1,z1-corner],material),
                box([x1-corner,y0,z0+corner],[x1,y1,z1-corner],material)]
    # Two slim leaves and a fine gasket: a card folio, rather than a deep box.
    e=plate(1.12,3.8,14.88,12.2,.06,.2,"case_edge")
    e+=plate(1,3.65,15,12.35,.16,.6,"case_shell",.36)
    e+=plate(1.08,3.7,14.92,12.3,.57,.68,"case_seal",.35)
    e+=plate(1.04,3.69,14.96,12.31,.65,1.13,"case_lid",.36)
    e+=plate(1.4,4.03,14.6,11.97,1.1,1.19,"case_shell",.28)
    # Small low-profile snap tabs bridge the seam without thick front buckles.
    for x in (3.3,11.45):
        e += [box([x,.42,3.5],[x+1.25,.89,3.8],"case_latch"),
              box([x+.16,.5,3.5],[x+1.09,.58,3.57],"case_edge"),
              box([x+.23,.86,3.6],[x+1.02,.96,3.86],"case_latch")]
    # Fine hinge knuckles meet both leaves along the rear edge.
    for x in (2.65,7.0,11.35):
        e += [box([x,.38,12.18],[x+2.0,.91,12.5],"case_edge"),
              box([x+.2,.48,12.34],[x+1.8,.81,12.5],"case_lid"),
              box([x+.91,.44,12.35],[x+1.09,.85,12.5],"case_seal")]
    # Restrained moulded lines and a small original card-outline embossing.
    for z in (4.9,9.85):
        e += [box([2.5,1.18,z],[13.5,1.23,z+.12],"case_edge")]
    e += [box([7.0,1.18,6.45],[7.14,1.25,9.2],"case_mark"),
          box([8.86,1.18,6.45],[9.0,1.25,9.2],"case_mark"),
          box([7.0,1.18,6.45],[9.0,1.25,6.59],"case_mark"),
          box([7.0,1.18,9.06],[9.0,1.25,9.2],"case_mark"),
          box([7.5,1.18,8.65],[8.5,1.25,8.79],"case_mark")]
    for x in (1.85,13.9):
        for z in (4.43,11.4):
            e += [box([x,1.1,z],[x+.24,1.22,z+.24],"case_edge"),
                  box([x+.04,1.2,z+.1],[x+.2,1.24,z+.14],"case_mark")]
    for x in (1.9,12.7):
        for z in (4.25,10.65):e += [box([x,0,z],[x+1.4,.09,z+1.05],"case_seal")]
    return e


def resources(ns,box,base_model,surface):
    textures={name:f"{ns}:block/cartridge_case/{name.removeprefix('case_')}" for name in PALETTE}
    model=base_model(geometry(box),textures)
    model['textures']={"particle":textures['case_shell'],**textures}
    model['display']['gui']={"rotation":[32,225,0],"translation":[0,1,0],"scale":[.95,.95,.95]}
    model['display']['firstperson_righthand']={"rotation":[0,35,0],"translation":[0,2,0],"scale":[.6,.6,.6]}
    model['display']['firstperson_lefthand']={"rotation":[0,225,0],"translation":[0,2,0],"scale":[.6,.6,.6]}
    return model,{f"textures/block/cartridge_case/{name.removeprefix('case_')}.png":surface(color) for name,color in PALETTE.items()}
