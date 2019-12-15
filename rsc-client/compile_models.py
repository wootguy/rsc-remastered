import glob, os, time, shutil, sys
from PIL import Image

qc_start = '''
$cd "."
$cdtexture "."
$scale 1.0

$flags 1024

$sequence "idle" {
	"idle"
	fps 0
}

$bodygroup "subsector"
{
'''

idle_smd = '''version 1
nodes
0 "root" -1
end
skeleton
time 0
0 0.000000 0.000000 0.000000 0.000000 0.000000 1.570796
end
'''

transparent_textures = ['wall_10.bmp', 'wall_12.bmp']

with open("idle.smd", "w") as f:
	f.write(idle_smd)

for file in glob.glob("*.bmp"):
	print("Converting %s" % file)
	img = Image.open(file)
	img.load()
	img = img.quantize()
	img.save(file)
		
for file in glob.glob("transparent/*.bmp"):
	shutil.copy(file, os.path.basename(file))

for file in glob.glob("sector_*_b00.smd"):
	mdl_name = file.split("_b00")[0]
	body1 = file.replace(".smd", "")
	
	qcpath = "mdl/" + mdl_name + ".qc"
	with open(qcpath, "w") as qc:
		qc.write('$modelname "' + mdl_name + '.mdl"\n')
		qc.write(qc_start)
		
		rendermode_textures = []
		
		for k in ['_b00', '_b01', '_b10', '_b11']:
			part = body1.replace("_b00", k)
			if os.path.exists(part + ".smd"):
				#qc.write('$bodygroup "subsector' + k + '"\n{\n')
				qc.write('\tstudio "%s"\n' % part)
				#qc.write("}\n")
				
				with open(part + ".smd") as f:
					for line in f.readlines():
						if '.bmp' in line:
							texName = line[:-1]
							if texName not in rendermode_textures and texName in transparent_textures:
								rendermode_textures.append(texName)
		
		qc.write("}\n")
		
		for tex in rendermode_textures:
			qc.write("$texrendermode " + tex + " masked\n")
		
	os.system('studiomdl_doom.exe ' + qcpath)
	
	if not os.path.exists(mdl_name + ".mdl"):
		print("\nUH OH FAILED TO COMPILE SOMETHING")
		sys.exit()
