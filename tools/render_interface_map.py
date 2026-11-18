"""Render Miro's annotated cards from unchanged Android screenshots and historical MAX crops."""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

root = Path(__file__).resolve().parents[1]
docs = root / "docs/interface-336"
comparison_only = sys.argv[1:] == ["--comparison-only"]
if not comparison_only:
    out, category, operator, report = map(Path, sys.argv[1:])
    out.mkdir(parents=True, exist_ok=True)
    external = dict(category=category, operator=operator, report=report)
fonts = root / "app/src/clean/res/font"
def font(size, bold=False):
    return ImageFont.truetype(str(fonts / ("manrope_heading.ttf" if bold else "golos_body.ttf")),size)

def wrap(d, value, face, width):
    lines = [""]
    for word in value.split():
        merged = (lines[-1]+" "+word).strip()
        if lines[-1] and d.textlength(merged,font=face)>width:
            lines.append(word)
        else:
            lines[-1]=merged
    return lines

pages=json.loads((docs/"transitions.json").read_text(encoding="utf8"))
for page, records in (() if comparison_only else pages.items()):
    for i, (sid,title,steps) in enumerate(records):
        path=external.get(sid,docs/"screens"/(sid+".png"))
        source=Image.open(path).convert("RGB")
        canvas=Image.new("RGB",(2400,2970),"#fffaf4")
        d=ImageDraw.Draw(canvas)
        d.text((100,64),f"SUSHI GO    /    {page}    /    {'MAX · АРХИВ' if sid in external else 'ANDROID  3.3.6'}",font=font(57,True),fill="#965576")
        d.text((100,155),title,font=font(90,True),fill="black")
        pic=ImageOps.contain(source,(1930,1660),Image.Resampling.LANCZOS)
        x=(2400-pic.width)//2
        y=325+(1660-pic.height)//2
        canvas.paste(pic,(x,y))
        d.rounded_rectangle((x-2,y-2,x+pic.width+2,y+pic.height+2),radius=7,outline="#443f3c",width=4)
        sy=2050
        for n,(px,py,caption) in enumerate(steps,1):
            if px is not None:
                tx,ty=int(x+px*pic.width),int(y+py*pic.height)
                bx=x-86 if px<.55 else x+pic.width+86
                d.line((bx,ty,tx,ty),fill="#bb1f68",width=7)
                d.ellipse((tx-10,ty-10,tx+10,ty+10),fill="#bb1f68",outline="white",width=3)
                d.ellipse((bx-35,ty-35,bx+35,ty+35),fill="#171416")
                d.text((bx,ty),str(n),font=font(41,True),anchor="mm",fill="white")
            d.rounded_rectangle((96,sy,180,sy+84),radius=25,fill="#f975b1")
            d.text((138,sy+40),str(n),font=font(49,True),anchor="mm",fill="black")
            lines=wrap(d,caption,font(65),2080)
            for j,line in enumerate(lines):
                d.text((220,sy+j*82),line,font=font(65),fill="black")
            sy+=max(115,len(lines)*82+30)
        d.line((100,2837,2300,2837),fill="#e6dcd4",width=4)
        d.text((100,2863),("ДАЛЕЕ   →" if i<len(records)-1 else "ПЕРЕХОДЫ   ↓"),font=font(52,True),fill="#94295b")
        canvas.quantize(colors=192, dither=Image.Dither.NONE).save(out/f"{page}-{i+1}.png",optimize=True)

before=Image.open(root/"docs/interface-335/screens/11-camera.png")
after=Image.open(docs/"screens/11-camera.png")
reference=Image.open(root/"docs/interface-335/reference.png")
# Only the camera device from the supplied three-screen reference; same viewport height throughout.
box=(int(reference.width*.025),int(reference.height*.15),int(reference.width*.327),int(reference.height*.984))
ref=reference.crop(box)
sheet=Image.new("RGB",(1030,760),"#fffaf4"); d=ImageDraw.Draw(sheet)
for i,(img,label) in enumerate(((before,"3.3.5    /    52,6%"),(after,"3.3.6    /    71,1%"),(ref,"Референс"))):
    sized=ImageOps.contain(img,(315,610),Image.Resampling.LANCZOS)
    sheet.paste(sized,(i*340+(340-sized.width)//2,88))
    d.text((i*340+22,24),label,font=font(25,True),fill="black")
sheet.save(docs/"comparison.jpg",quality=92)
