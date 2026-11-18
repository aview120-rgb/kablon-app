import sys, pyembroidery as pe
for f in sys.argv[1:]:
    p = pe.read(f)
    if p is None: print(f, 'FAILED TO READ'); continue
    b = p.bounds()
    cmds = {}
    for s in p.stitches:
        c = s[2] & pe.COMMAND_MASK
        cmds[c] = cmds.get(c, 0) + 1
    names = {pe.STITCH:'stitch', pe.JUMP:'jump', pe.TRIM:'trim', pe.COLOR_CHANGE:'color', pe.END:'end', pe.STOP:'stop'}
    print(f, 'threads', len(p.threadlist), 'size mm %.1fx%.1f' % ((b[2]-b[0])/10, (b[3]-b[1])/10), {names.get(k,k):v for k,v in cmds.items()})
    pe.write_png(p, f + '.png', {"linewidth": 2})
