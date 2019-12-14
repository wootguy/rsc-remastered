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

$bodygroup "body"
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

for file in glob.glob("sector_*0_b0.smd"):
	print("CHECKING " + file)
	mdl_name = file.split("_0_b0")[0]
	body1 = file.replace(".smd", "")
	body2 = body1.replace("_b0", "_b1")
	body3 = body1.replace("_b0", "_b2")
	
	qcpath = "mdl/" + mdl_name + ".qc"
	with open(qcpath, "w") as qc:
		qc.write('$modelname "' + mdl_name + '.mdl"\n')
		qc.write(qc_start)
		
		qc.write('$bodygroup "landscape0"\n{\n')
		qc.write('\tstudio "%s"\n' % body1)
		qc.write("}\n")
		
		qc.write('$bodygroup "landscape1"\n{\n')
		qc.write('\tstudio "%s"\n' % body2)
		qc.write("}\n")
		
		rendermode_textures = []
		
		for k in ['_0_b2', '_0_b3', '_0_b4', 
				  '_1_b0', '_1_b1', '_1_b2', '_1_b3',
				  '_2_b0', '_2_b1', '_2_b2', '_2_b3',
				  '_3_b0', '_3_b1', '_3_b2', '_3_b3']:
			part = body1.replace("_0_b0", k)
			if os.path.exists(part + ".smd"):
				qc.write('$bodygroup "objects' + k + '"\n{\n')
				qc.write('\tstudio "%s"\n' % part)
				qc.write("}\n")
				
				with open(part + ".smd") as f:
					for line in f.readlines():
						if '.bmp' in line:
							texName = line[:-1]
							if texName not in rendermode_textures and texName in transparent_textures:
								rendermode_textures.append(texName)
		
		for tex in rendermode_textures:
			qc.write("$texrendermode " + tex + " masked\n")
		
	os.system('studiomdl_doom.exe ' + qcpath)
	
	if not os.path.exists(mdl_name + ".mdl"):
		print("\nUH OH FAILED TO COMPILE SOMETHING")
		sys.exit()
