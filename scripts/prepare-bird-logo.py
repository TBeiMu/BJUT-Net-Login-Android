from pathlib import Path
import argparse
import hashlib
import json
import numpy as np
from PIL import Image, ImageDraw

parser = argparse.ArgumentParser(description='Derive launcher and monochrome assets from the supplied logo.')
parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
root = parser.parse_args().root
source = root/'docs/assets/logo-bird-source.png'
image = Image.open(source).convert('RGB')
rgb = np.asarray(image)
chroma = rgb.max(axis=2).astype(int) - rgb.min(axis=2).astype(int)
colored = (chroma > 42) & (rgb.min(axis=2) < 200)
ys, xs = np.nonzero(colored)
box = (int(xs.min()) - 3, int(ys.min()) - 3, int(xs.max()) + 4, int(ys.max()) + 4)

# Preserve the white cheeks, eyes and sweeping belly. They are connected to the original
# background in places, so use an explicit face enclosure instead of removing all white pixels.
white_face = Image.new('L', image.size)
ImageDraw.Draw(white_face).polygon([(367,574),(400,510),(425,443),(465,401),(512,386),(553,401),
    (577,452),(584,478),(635,433),(686,414),(742,427),(780,470),(801,545),(787,599),
    (831,602),(872,640),(847,678),(786,718),(713,766),(622,817),(552,864),(503,913),
    (445,909),(438,870),(457,817),(493,767),(548,720),(496,709),(436,685),(390,642)],fill=255)
alpha = np.maximum(colored.astype(np.uint8)*255, np.asarray(white_face))
# Keep the source color and anti-alias the exterior by downsampling once.
color = Image.fromarray(np.dstack([rgb, alpha]), 'RGBA').crop(box)
docs = root/'docs/assets'; docs.mkdir(exist_ok=True)
color.save(docs/'logo-bird-foreground.png', optimize=True)

def fit(content, canvas_size, fraction):
    result = Image.new('RGBA',(canvas_size,canvas_size))
    tile = content.copy()
    tile.thumbnail((round(canvas_size*fraction), round(canvas_size*fraction)), Image.Resampling.LANCZOS)
    result.alpha_composite(tile,((canvas_size-tile.width)//2,(canvas_size-tile.height)//2))
    return result

res = root/'app/src/main/res'
(res/'drawable-nodpi').mkdir(exist_ok=True)
fit(color,432,0.61).save(res/'drawable-nodpi/ic_launcher_foreground.png',optimize=True)
image.resize((600,600),Image.Resampling.LANCZOS).save(res/'drawable-nodpi/logo_bird.png',optimize=True)

# Deterministic silhouette paths from the source's color regions. Trace pixel boundaries
# and simplify collinear/near-collinear points; no unrelated generated artwork is introduced.
def paths(mask, width=300):
    small=Image.fromarray(mask.astype(np.uint8)*255).resize((width,round(mask.shape[0]*width/mask.shape[1])),Image.Resampling.NEAREST)
    a=np.asarray(small)>127
    edges={}
    def add(start,end):
        edges.setdefault(start,[]).append(end)
    h,w=a.shape
    for y,x in zip(*np.nonzero(a)):
        if y==0 or not a[y-1,x]: add((x,y),(x+1,y))
        if x==w-1 or not a[y,x+1]: add((x+1,y),(x+1,y+1))
        if y==h-1 or not a[y+1,x]: add((x+1,y+1),(x,y+1))
        if x==0 or not a[y,x-1]: add((x,y+1),(x,y))
    loops=[]
    while edges:
        start=next(iter(edges)); p=start; points=[]; previous=None
        def direction(start,end):
            dx,dy=end[0]-start[0],end[1]-start[1]
            return {(1,0):0,(0,1):1,(-1,0):2,(0,-1):3}[(dx,dy)]
        while p in edges:
            points.append(p)
            candidates=edges[p]
            next_p=min(candidates,key=lambda q:[1,0,3,2].index((direction(p,q)-previous)%4)) if previous is not None else candidates[0]
            previous=direction(p,next_p)
            candidates.remove(next_p)
            if not candidates: del edges[p]
            p=next_p
            if p==start: break
        if len(points)<12: continue
        area=abs(sum(points[i][0]*points[(i+1)%len(points)][1]-points[(i+1)%len(points)][0]*points[i][1] for i in range(len(points))))/2
        if area<10: continue
        # Ramer-Douglas-Peucker on an open loop split at its farthest point.
        def simplify(pts,epsilon=1.1):
            if len(pts)<3:return pts
            p0=np.array(pts[0],float); delta=np.array(pts[-1],float)-p0
            inner=np.asarray(pts[1:-1],float)-p0
            length=np.linalg.norm(delta)
            if length:
                t=np.clip(inner@delta/(length*length),0,1)
                distances=np.linalg.norm(inner-t[:,None]*delta,axis=1)
            else: distances=np.linalg.norm(inner,axis=1)
            index=int(np.argmax(distances))+1
            if distances[index-1]>epsilon: return simplify(pts[:index+1])+simplify(pts[index:])[1:]
            return [pts[0],pts[-1]]
        opposite=int(np.argmax(np.linalg.norm(np.asarray(points)-np.asarray(points[0]),axis=1)))
        reduced=simplify(points[:opposite+1])+simplify(points[opposite:]+[points[0]])[1:]
        loops.append(reduced)
    return loops,w,h

def path_text(loops):
    return ''.join('M'+','.join(map(str,p[0]))+''.join('L'+','.join(map(str,x)) for x in p[1:])+'Z' for p in loops)

cropped_rgb=np.asarray(color)[:,:,:3]
crop_chroma=cropped_rgb.max(axis=2).astype(int)-cropped_rgb.min(axis=2).astype(int)
body=(np.asarray(color)[:,:,3]>0)
navy=(cropped_rgb[:,:,2].astype(int)>cropped_rgb[:,:,0].astype(int)+30)&(cropped_rgb[:,:,1]<110)&(crop_chroma>42)&body
teal=(cropped_rgb[:,:,1]>100)&(cropped_rgb[:,:,2]>100)&(cropped_rgb[:,:,0]<100)&body
gold=(cropped_rgb[:,:,0]>210)&(cropped_rgb[:,:,1]>100)&(cropped_rgb[:,:,2]<100)&body
layers=[]
for name,mask,tint in [('white',body,'#FFFFFF'),('navy',navy,'#123F72'),('teal',teal,'#109EB2'),('gold',gold,'#FFBC24')]:
    loops,w,h=paths(mask)
    layers.append((name,path_text(loops),tint))

def vector(width,height,viewport,group,tints):
    attrs=f'android:width="{width}dp" android:height="{height}dp" android:viewportWidth="{viewport}" android:viewportHeight="{viewport}"'
    return '<?xml version="1.0" encoding="utf-8"?>\n<!-- Derived from the user-supplied bird logo; regenerate with scripts/prepare-bird-logo.py. -->\n'+f'<vector xmlns:android="http://schemas.android.com/apk/res/android" {attrs}>\n'+group+'\n'+''.join(f'        <path android:fillType="evenOdd" android:fillColor="{tints.get(name,tint)}" android:pathData="{data}" />\n' for name,data,tint in layers if name in tints)+'    </group>\n</vector>\n'

sx=108*0.61/w; sy=sx
tx=(108-w*sx)/2;ty=(108-h*sy)/2
group=f'    <group android:scaleX="{sx:.7f}" android:scaleY="{sy:.7f}" android:translateX="{tx:.5f}" android:translateY="{ty:.5f}">'
# Monochrome keeps the eyes/white cheek openings; it uses source color regions as one ink shape.
mono=vector(108,108,108,group,{k:'#FFFFFFFF' for k in ['navy','teal','gold']})
(res/'drawable/ic_launcher_monochrome.xml').write_text(mono,encoding='utf-8',newline='\n')
ns=20/w;ntx=(24-w*ns)/2;nty=(24-h*ns)/2
ngroup=f'    <group android:scaleX="{ns:.7f}" android:scaleY="{ns:.7f}" android:translateX="{ntx:.5f}" android:translateY="{nty:.5f}">'
(res/'drawable/ic_notification_mark.xml').write_text(vector(24,24,24,ngroup,{k:'#FFFFFFFF' for k in ['navy','teal','gold']}),encoding='utf-8',newline='\n')

print(json.dumps({'sourceSize':image.size,'foregroundBox':box,'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'vectorSize':(w,h),'vectorPaths':[(name,len(data)) for name,data,_ in layers]}))
