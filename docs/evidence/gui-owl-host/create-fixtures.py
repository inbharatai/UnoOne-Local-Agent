"""Recreate historical fixture PNGs only into a new empty directory; never run original prepare scripts."""
from pathlib import Path
import sys
from PIL import Image,ImageDraw,ImageFont
D=Path(sys.argv[1]); D.mkdir(parents=True,exist_ok=False)
f=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',16)
s=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',11)
for id in ['', 'A', 'B']:
 im=Image.new('RGB',(256,256),'#f3eee4');d=ImageDraw.Draw(im)
 d.text((12,10),'SYNTHETIC GUI TEST'+(' '+id if id else ''),font=s,fill='#222222')
 buttons=[('Home',(12,45,112,97),'#255b96'),('Settings',(12,145,112,205),'#747474'),('Search',(134,145,246,205),'#bc153f')] if id!='B' else [('Search',(12,45,124,105),'#bc153f'),('Settings',(12,145,112,205),'#747474'),('Home',(134,145,246,205),'#255b96')]
 for label,box,color in buttons:
  d.rounded_rectangle(box,radius=8,fill=color)
  d.text(((box[0]+box[2])/2,(box[1]+box[3])/2),label,font=f,fill='white',anchor='mm')
 im.save(D/('synthetic-gui'+('-'+id if id else '')+'-256.png'))
Image.new('RGB',(256,256),'red').save(D/'synthetic-red-256.png')
