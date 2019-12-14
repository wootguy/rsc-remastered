package client.game.world;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3i;

import client.game.scene.Model;
import client.res.Resources;
import client.res.Texture;
import client.util.DataUtils;

public class ModelExporter {
	
	final int SECTOR_WIDTH = World.NUM_TILES_X/2;
    final int SECTOR_HEIGHT = World.NUM_TILES_Z/2;
    static final float WORLD_SCALE = 0.5f;
    static Vector3f WORLD_OFFSET = new Vector3f(-16*96, -16*96, -512);
    static final float UNDERGROUND_DEPTH = 1024;
    
    public final static String SMD_HEADER = "version 1\n" +
			"nodes\n" +
			"0 \"root\" -1\n" +
			"end\n" +
			"skeleton\n" +
			"time 0\n" +
			"0 0.000000 0.000000 0.000000 0.000000 0.000000 0.000000\n" +
			"end\n" +
			"triangles\n";
    
	int sectorX;
	int sectorZ;
	int layer;
	WorldLoader worldLoader;
	
	String smdPrefix;
	
	static final int SECTOR_SIZE = World.NUM_TILES_X/2+1;
	static final int SECTOR_OFFSET = 24;
	
	// split sectors into even smaller chunks for smaller entity updates in-game (prevent desyncs)
	static final int SUB_SECTOR_COUNT = 2; // per axis
	static final int SUB_SECTOR_SIZE = (World.NUM_TILES_X/2)/2;
	
	// export models with sector offsets so they can be loaded together as one giant model in blender
	public static final boolean superModelMode = false;
	
	static class ExportLayer {
		Model terrain;
		Model walls;
		Model roofs;
		
		Vector3f[] terrainNormals;
		Vector3f[] bridgeNormals;
		Vector3f[] roofNormals;
		
		List<Boolean> roofFlags = new ArrayList<>();
		
		ExportLayer() {}
	}
	
	static class Vertex {
		float x, y, z;
		float nx, ny, nz;
		float u, v;
		
		Vertex(float x, float y, float z) {			
			// convert coordinate system and shift into sector offset (or world origin if smd mode)
			this.x = x;
        	this.y = z;
        	this.z = -y;
		}
		
		Vertex(Vector3f pos, Vector3f normal, Vector2f uv) {
			this.u = uv.x;
			this.v = uv.y;
			
			this.x = pos.x;
        	this.y = pos.z;
        	this.z = -pos.y;
        	
        	this.nx = normal.x;
        	this.ny = normal.y;
        	this.nz = normal.z;
		}
		
		Vertex(Vertex other) {
			x = other.x;
			y = other.y;
			z = other.z;
			nx = other.nx;
			ny = other.ny;
			nz = other.nz;
			u = other.u;
			v = other.v;
		}
		
		Vector3f getPos() {
			return new Vector3f(x, y, z);
		}
		
		String toSmdString() {
			return "0 " + (x*WORLD_SCALE + WORLD_OFFSET.x) + " " 
					+ (y*WORLD_SCALE + WORLD_OFFSET.y) + " " 
					+ (z*WORLD_SCALE + + WORLD_OFFSET.z) + " " 
					+ nx + " " + ny + " " + nz + " " 
					+ u + " " + v + "\n";
		}
	}
	
	static class Triangle {
		Vertex[] verts = new Vertex[3];
		String texture;
		
		Triangle(Model model, int faceIdx, boolean secondHalfOfQuad, Vector3f[] normals) {
			int[] order = {2, 1, 0};
			
			if (secondHalfOfQuad) {
				order[0] = 3;
				order[1] = 2;
				order[2] = 0;
			}
			
			for (int i = 0; i < 3; i++) {
				int vertIdx = model.faceVertices[faceIdx][order[i]];
				Vector3i iv0 = model.vertices[vertIdx];
				Vector3f pos = new Vector3f(iv0.x, iv0.y, iv0.z);
				verts[i] = new Vertex(pos, normals[vertIdx], new Vector2f());
			}
		}
		
		Triangle(Vertex v0, Vertex v1, Vertex v2, String texture) {
			verts[0] = new Vertex(v0);
			verts[1] = new Vertex(v1);
			verts[2] = new Vertex(v2);
			this.texture = texture;
		}
		
		Triangle(Vector3f v0, Vector3f v1, Vector3f v2, String texture) {
			verts[0] = new Vertex(v0.x, v0.y, v0.z);
			verts[1] = new Vertex(v1.x, v1.y, v1.z);
			verts[2] = new Vertex(v2.x, v2.y, v2.z);
			this.texture = texture;
		}
		
		Triangle flip() {
			Triangle tri = new Triangle(verts[2], verts[1], verts[0], texture);
			
			for (int i = 0; i < 3; i++){
				tri.verts[i].nx *= -1;
				tri.verts[i].ny *= -1;
				tri.verts[i].nz *= -1;
			}
			return tri;
		}
		
		// calculate texture coordinates given a top-down view (floor textures)
		void applyTopDownUV(boolean flip) {
			float maxX = -99999.0f;
			float maxZ = -99999.0f;
			float minX = 99999.0f;
			float minZ = 99999.0f;
			for (int k = 0; k < 3; k++) {
				Vertex vert = verts[k];
				if (vert.x > maxX) { maxX = vert.x; }
				if (vert.y > maxZ) { maxZ = vert.y; }
				if (vert.x < minX) { minX = vert.x; }
				if (vert.y < minZ) { minZ = vert.y; }
			}
    		
			for (int k = 0; k < 3; k++) {
				float u = 1;
				float v = 0;
				Vertex vert = verts[k];
				
				if (Math.abs(vert.x - maxX) < Math.abs(vert.x - minX)) {
					v = 1;
				}
				if (Math.abs(vert.y - maxZ) < Math.abs(vert.y - minZ)) {
					u = 0;
				}
				
				if (flip) {
					float tmp = u;
					u = v;
					v = tmp;
				}
				
				vert.u = u;
				vert.v = v;
			}
		}
	}
	
	ModelExporter(WorldLoader worldLoader, int sectorX, int sectorZ, int layer) {
		this.worldLoader = worldLoader;
		this.sectorX = sectorX;
		this.sectorZ = sectorZ;
		this.layer = layer;
		
		this.smdPrefix = "sector_" + sectorX + "_" + sectorZ + "_" + layer;
		
		
	}
	
	public int getLandscapeHeight(int[][] landscapeHeights, Vertex vert) {
		int wx = (int)Math.round((vert.x) / 128.0f);
		int wy = (int)Math.round((vert.y) / 128.0f);
		return landscapeHeights[(SECTOR_SIZE+1)-wx][(SECTOR_SIZE+1)-wy] + 192*layer;
	}
	
	private static void clipModel(Model model, int minCoord, int maxCoord) {
		for (int i = 0; i < model.numFaces; i++) {
			
			boolean isOutOfBounds = false;
			for (int k = 0; k < model.numVerticesPerFace[i]; k++) {
				Vector3i vert = model.vertices[model.faceVertices[i][k]];
				if (!vertexInBounds(vert, minCoord, maxCoord)) {
					isOutOfBounds = true;
					vert.x = vert.y = vert.z = 0; // prevent out of bounds errors later
				}
			}
			
			if (isOutOfBounds) {
				model.numVerticesPerFace[i] = 0;
			}
		}
	}
	
	private static void shiftModel(Model model, int offset) {
		for (int i = 0; i < model.numVertices; i++) {
			Vector3i vert = model.vertices[i];
			vert.x += offset;
			vert.z += offset;
		}
	}
	
	private static boolean vertexInBounds(Vector3i vert, int minCoord, int maxCoord) {
		int x = Math.round((float)vert.x/128.0f);
		int z = Math.round((float)vert.z/128.0f);
		if (x >= maxCoord || z >= maxCoord || x <= minCoord || z <= minCoord) {
			return false;
		}
		return true;
	}
	
	private static void printModelBounds(Model model) {
		Vector3i mins = new Vector3i(999999, 999999, 99999);
		Vector3i maxs = new Vector3i(-999999, -999999, -99999);
		for (int i = 0; i < model.numVertices; i++) {
			Vector3i vert = model.vertices[i];
			if (vert.x > maxs.x) maxs.x = vert.x;
			if (vert.z > maxs.z) maxs.z = vert.z;
			if (vert.x < mins.x) mins.x = vert.x;
			if (vert.z < mins.z) mins.z = vert.z;
		}
		System.out.println("Model bounds: " + mins.x/128.0f + " " + mins.z/128.0f 
				+ " TO " + maxs.x/128.0f + " " + maxs.z/128.0f);
	}
	
	private static int[][] getLandscapeHeights(Model model) {
		int[][] landscapeHeights = new int[SECTOR_SIZE+2][SECTOR_SIZE+2];
		
		for (int x = 0; x < SECTOR_SIZE+2; x++) {
			for (int y = 0; y < SECTOR_SIZE+2; y++) {
				landscapeHeights[x][y] = -1;
			}
		}
		
		for (int i = 0; i < model.numVertices; i++) {
			Vector3i ivert = model.vertices[i];
			Vertex vert = new Vertex(ivert.x, ivert.y, ivert.z);
			
			int wx = (int)Math.round((vert.x) / 128.0f);
			int wy = (int)Math.round((vert.y) / 128.0f);
			
			wx = (SECTOR_SIZE+1) - wx;
			wy = (SECTOR_SIZE+1) - wy;
			
			landscapeHeights[wx][wy] = (int)vert.z;
		}
		
		// fill in holes in the map (the ones that lead to dungeons)
		// fixes singularity in the mansion
		int lastValidHeight = 0;
		for (int y = 0; y < SECTOR_SIZE+2; y++) {
			for (int x = 0; x < SECTOR_SIZE+2; x++) {
				if (landscapeHeights[x][y] == -1) {
					// hopefully the hole isn't slanted and also has a roof/wall over it
					// also this won't work for some holes on the edges
					landscapeHeights[x][y] = lastValidHeight;
				}
				lastValidHeight = landscapeHeights[x][y];
			}
		}
		
		return landscapeHeights;
	}
	
	private static void removeRoofsThatOverlapFloors(Model roofs, Model floors) {
		for (int i = 0; i < roofs.numFaces; i++) {
			
			for (int k = 0; k < floors.numFaces; k++) {
				
				if (roofs.numVerticesPerFace[i] == floors.numVerticesPerFace[k]) {
					boolean overlapping = true;
					
					boolean[] alreadyMatched = new boolean[roofs.numVerticesPerFace[i]];
					
					for (int j = 0; j < roofs.numVerticesPerFace[i]; j++) {
						Vector3i roof_vert = roofs.vertices[roofs.faceVertices[i][j]];
						boolean overlapsVert = false;
						for (int j2 = 0; j2 < floors.numVerticesPerFace[k]; j2++) {
							Vector3i floor_vert = floors.vertices[floors.faceVertices[k][j2]];
							int diffX = Math.abs(roof_vert.x - floor_vert.x);
							int diffY = Math.abs(roof_vert.z - floor_vert.z);
							if (!alreadyMatched[j2] && diffX == 0 && diffY == 0) {
								overlapsVert = true;
								alreadyMatched[j2] = true;
								break;
							}
						}
						
						if (!overlapsVert) {
							overlapping = false;
							break;
						}
					}
					if (overlapping) {
						roofs.numVerticesPerFace[i] = 0;
					}
				}
			}
		}
	}
	
	// for preventing roofs from clipping through walls on upper levels
	private static void snapRoofsToNearbyWalls(Model roofs, Model walls) {
		for (int i = 0; i < roofs.numVertices; i++) {
			Vector3i roof_vert = roofs.vertices[i];
			
			for (int k = 0; k < walls.numVertices; k++) {
				Vector3i wall_vert = walls.vertices[k];
				int diffX = Math.abs(roof_vert.x - wall_vert.x);
				int diffY = Math.abs(roof_vert.y - wall_vert.y);
				int diffZ = Math.abs(roof_vert.z - wall_vert.z);
				
				if ((diffX > 0 || diffZ > 0) && diffX < 64 && diffZ < 64) {
					roof_vert.x = wall_vert.x;
					roof_vert.z = wall_vert.z;
				}
			}
		}
	}
	
	// TODO:
	// normals don't blend right across sectors
	// no water in underground areas
	public static void exportSector(WorldLoader loader, int sectorX, int sectorZ,
			FileWriter terrainSmd, FileWriter wallSmd, FileWriter roofSmd, FileWriter floorSmd) throws IOException {		
		System.out.println("Exporting Sector: " + sectorX + " " + sectorZ);

		if (superModelMode) {
			// TODO: Shift x axis (32768-4096) so f2p world is centered in hammer
	        WORLD_OFFSET.x = (sectorX - 50)*32*96;
	        WORLD_OFFSET.y = (sectorZ - 50)*32*96;
		}
		
		
        List<ExportLayer> layers = new ArrayList<>();
		layers.add(loader.loadLayer(sectorX, sectorZ, 0, true));
		layers.add(loader.loadLayer(sectorX, sectorZ, 1, true));
		layers.add(loader.loadLayer(sectorX, sectorZ, 2, true));
		layers.add(loader.loadLayer(sectorX, sectorZ, 3, true));
		
		// game renders 2x2 sectors, but only the middle is free from glitches, mostly.
		// 1 tile away from the border is usually fine, but varrock had different roof heights
		// for the same location when rendered from a different 2x2 sector,
		// This extracts a single 48x48 sector from the middle of a 2x2 sector area (96x96).
		System.out.println("Calculating normals");
		for (int j = 0; j < layers.size(); j++) {
			ExportLayer layer = layers.get(j);
					
			shiftModel(layer.terrain, -SECTOR_OFFSET*128);
			shiftModel(layer.walls, -SECTOR_OFFSET*128);
			shiftModel(layer.roofs, -SECTOR_OFFSET*128);
			
			// clip with 1 tile border so normals can be smoothed across sectors
			// TODO: Still doesn't work. Probably a rendering glitch like with the varrock roofs.
			clipModel(layer.terrain, -1, SECTOR_SIZE+2);
			clipModel(layer.walls, -1, SECTOR_SIZE+2);
			clipModel(layer.roofs, -1, SECTOR_SIZE+2);
			
			layer.terrain.relight();
			layer.roofs.relight();
			layer.terrainNormals = calculateNormals(layer.terrain, 0xbc614e, true);
			layer.bridgeNormals = calculateNormals(layer.terrain, 0xbc614e, false);
			layer.roofNormals = calculateNormals(layer.roofs, 0, false);
			
			// clip to final range
			clipModel(layer.terrain, 0, SECTOR_SIZE+1);
			clipModel(layer.walls, 0, SECTOR_SIZE+1);
			clipModel(layer.roofs, 0, SECTOR_SIZE+1);
			//printModelBounds(layer.terrain);
			
			
		}
		for (int j = 0; j < layers.size(); j++) {
			if (j < 2) {
				removeRoofsThatOverlapFloors(layers.get(j).roofs, layers.get(j+1).terrain);
				snapRoofsToNearbyWalls(layers.get(j).roofs, layers.get(j+1).walls);
			}
		}
		
		int[][] landscapeHeights = getLandscapeHeights(layers.get(0).terrain);
		
		FileWriter[][] smdBodies = new FileWriter[SUB_SECTOR_COUNT][SUB_SECTOR_COUNT];
		if (!superModelMode) {
			for (int x = 0; x < SUB_SECTOR_COUNT; x++) {
				for (int y = 0; y < SUB_SECTOR_COUNT; y++) {
					String smd_path = "sector_" + sectorX + "_" + sectorZ + "_b" + x + "" + y + ".smd";
					smdBodies[x][y] = new FileWriter(smd_path);
					smdBodies[x][y].write(SMD_HEADER);	
				}
			}
		}
		
		for (int j = 0; j < layers.size(); j++) {
			ExportLayer layer = layers.get(j);
			
			if (j > 0) {
				// this is somehow 100% accurate. Slanted roofs cause slanted walls and gaps between layers.
				for (int k = 0; k < layers.get(j-1).roofs.numVertices; k++) {
					Vector3i ivert = layers.get(j-1).roofs.vertices[k];
					Vertex vert = new Vertex(ivert.x, ivert.y, ivert.z);
					int wx = (SECTOR_SIZE+1) - (int)Math.round((vert.x) / 128.0f);
					int wy = (SECTOR_SIZE+1) - (int)Math.round((vert.y) / 128.0f);
					int height = (int)vert.z - 192;
					if (j == 1) {
						// height of first layer roofs depends on terrain
						height -= landscapeHeights[wx][wy];
					}
					landscapeHeights[wx][wy] += height;
				}
				// hack-fix for wizard tower that has extra tall first floor
				if ((sectorX == 52 || sectorX == 53) && (sectorZ == 51 || sectorZ == 52) && j == 1) {
					for (int y = 0; y < SECTOR_SIZE+2; y++) {
						for (int x = 0; x < SECTOR_SIZE+2; x++) {
							landscapeHeights[x][y] += 83;
						}
					}
				}
			}
			
			ModelExporter modelExporter = new ModelExporter(loader, sectorX, sectorZ, j);
			if (layer.terrain != null)
				modelExporter.exportTerrain(layer.terrain, landscapeHeights, layer.terrainNormals, 
						layer.bridgeNormals, terrainSmd, floorSmd, smdBodies);
			if (layer.walls != null)
				modelExporter.exportWalls(layer.walls, landscapeHeights, wallSmd, smdBodies);
			if (layer.roofs != null)
				modelExporter.exportRoofs(layer.roofs, landscapeHeights, layer.roofFlags, 
						layer.roofNormals, roofSmd, smdBodies);
		}
		
		if (!superModelMode) {
			for (int x = 0; x < SUB_SECTOR_COUNT; x++) {
				for (int y = 0; y < SUB_SECTOR_COUNT; y++) {
					smdBodies[x][y].close();
				}
			}
		}
	}
	
	public static void writeAllTextures() throws IOException {
		for (int i = 0; i < Resources.textures.length; i++) {
			String name = "tex_" + i + ".bmp";
			writeTexture(Resources.textures[i], name);
		}
	}
	
	private void writeTerrainTexture() throws IOException {
		if (superModelMode)
			return;
		
		worldLoader.setCurrentSector(sectorX, sectorZ, layer);
		
		// 1 pixel passed border to prevent seams between sectors caused by texture filtering
    	int border = 1;
    	
    	int scale = 2;	// scaled up a little to make linear filtering look more like GL_NEAREST
    	int imgWidth = (SECTOR_WIDTH+border*2)*scale;
    	int imgHeight = (SECTOR_HEIGHT+border*2)*scale;
    	BufferedImage theImage = new BufferedImage(imgWidth, imgHeight, 
    			BufferedImage.TYPE_INT_RGB);
    	
    	int mapOffset = SECTOR_OFFSET+1;
    	
        for (int x = -border; x < SECTOR_WIDTH+border; x++) {
            for (int z = -border; z < SECTOR_HEIGHT+border; z++) {            	
        		int groundTexture = worldLoader.world.getGroundTexture(x+ mapOffset, z+ mapOffset);
        		int groundTextureOverlay = worldLoader.world.getGroundTextureOverlay(x+ mapOffset, z+ mapOffset);
                
            	int c = DataUtils.rscColorToRgbColor(worldLoader.GROUND_COLOURS[groundTexture]);
            	
            	// 1 = road
            	// 2 = water
            	// 3 = building foundation?
            	// 4 = water under bridge?
            	// 9 = mountain side
            	if (groundTextureOverlay > 0 && false) {
                	int tileType1 = Resources.getTileDef(groundTextureOverlay - 1).getType();
                    int tileType2 = worldLoader.getTileType(x, z);
                	c = Resources.getTileDef(groundTextureOverlay - 1).getColour();
                	if (groundTextureOverlay == 14) {
            			//System.out.println("ZOMG bank: " + c);
                	}
                	c = DataUtils.rscColorToRgbColor(c);
            		
                	//System.out.println("LE OVERLAY: " + groundTextureOverlay);
            	}
            	
            	for (int px = (x+border)*scale; px < (x+border)*scale+scale; px++) {
            		for (int py = (z+border)*scale; py < (z+border)*scale+scale; py++) {
            			theImage.setRGB((imgWidth-1) - px, py, c);
            		}
            	}
            }
        }
        
        ImageIO.write(theImage, "BMP", new File("sector_" + sectorX + "_" + sectorZ + "_" + layer + ".bmp"));
	}
	
	private static void writeTexture(Texture tex, String bmp_name) throws IOException {
		if (superModelMode)
			return;
		
		int scale = 1;
		int border = 0;
		
		if (Files.notExists(new File(bmp_name).toPath())) {
			int textureSize = !tex.isLarge() ? 64 : 128;
			
			BufferedImage theImage = new BufferedImage(textureSize*scale, textureSize*scale, 
        			BufferedImage.TYPE_INT_RGB);
			
			// Produce texture by looking up colours in the palette
	        for (int y = 0; y < textureSize; y++) {
	            for (int x = 0; x < textureSize; x++) {
	                int colourIndex = tex.colourData[x + y * textureSize] & 0xff;
	                int texColour = tex.palette[colourIndex];
	                texColour &= 0xf8f8ff;
	                if (texColour == 0) {
	                    texColour = 1;
	                } else if (texColour == 0xf800ff) {
	                    texColour = 0;
	                    tex.setHasTransparency(true);
	                }
	                if (texColour == 0) {
	                	texColour = 255; // bright blue
	                }

					// rotate to normal orientation while writing
	                for (int px = y*scale; px < y*scale+scale; px++) {
	            		for (int py = ((textureSize-1)-x)*scale; py < ((textureSize-1)-x)*scale+scale; py++) {
	            			theImage.setRGB(px, py, texColour);
	            		}
	            	}
	                
	            }
	        }
			
            ImageIO.write(theImage, "BMP", new File(bmp_name));
		}
	}
	
	private void writeSolidColorTexture(String bmp_name, int color) throws IOException {
		if (Files.notExists(new File(bmp_name).toPath())) {
			int textureSize = 1;
			BufferedImage theImage = new BufferedImage(textureSize, textureSize, BufferedImage.TYPE_INT_RGB);
			
	        for (int y = 0; y < textureSize; y++) {
	            for (int x = 0; x < textureSize; x++) {
	                theImage.setRGB(y, (textureSize-1)-x, color);
	            }
	        }
			
            ImageIO.write(theImage, "BMP", new File(bmp_name));
		}
	}
	
	public static Vector3f[] calculateNormals(Model model, int fillFaceFilter, boolean invertFilter) {
		Vector3f[] normals = new Vector3f[model.numVertices];
		
		// TODO: why not iterate vertices
		for (int i = 0; i < model.numFaces; i++) {
        	float nx = 0;
        	float ny = 0;
        	float nz = 0;
        	
        	if (fillFaceFilter != 0) {
        		if (!invertFilter && model.faceFillBack[i] != fillFaceFilter)
        			continue;
        		else if (invertFilter && model.faceFillBack[i] == fillFaceFilter)
        			continue;	
        	}
        	
        	for (int k = model.numVerticesPerFace[i]-1; k >= 0; k--) {
        		int idx = model.faceVertices[i][k];
            	
        		if (normals[idx] == null) {
        			int totalFaces = 0;
        			
        			// calculate smoothed normal (average of all connected face normals)
        			for (int f = 0; f < model.numFaces; f++) {
        				if (fillFaceFilter != 0) {
        	        		if (!invertFilter && model.faceFillBack[f] != fillFaceFilter)
        	        			continue;
        	        		else if (invertFilter && model.faceFillBack[f] == fillFaceFilter)
        	        			continue;	
        	        	}
        				
	            		boolean faceUsesVert = false;
	            		for (int j = 0; j < model.numVerticesPerFace[f]; j++) {
    	            		int idx2 = model.faceVertices[f][j];
    	            		if (idx2 == idx) {
    	            			faceUsesVert = true;
    	            			break;
    	            		}
    	            	}
	            		
	            		if (faceUsesVert) {
	            			Vector3i vec = model.faceNormals[f];
	            			float dist = (float)Math.sqrt(vec.x*vec.x + vec.y*vec.y + vec.z*vec.z);
	            			nx += (float)vec.x / dist;
	            			ny += (float)vec.z / dist;
	            			nz += (float)vec.y / dist;
	            			totalFaces++;
	            		}
    	            }
        			
        			if (totalFaces > 0) {
    	            	nx /= (float)totalFaces;
    	            	ny /= (float)totalFaces;
    	            	nz /= (float)totalFaces;
    	            	
    	            	float dist = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
            			nx /= dist;
            			ny /= dist;
            			nz /= dist;
	            	}
        			
        			normals[idx] = new Vector3f(nx, ny, nz);
        		}
        	}
		}
		
		return normals;
	}
	
	public boolean isEmpty(Model model) {
		if (model.numFaces == 0) {
			return true;
		}
		// empty sectors appear as ocean tiles (every vertex at sea level)
		for (int i = 0; i < model.numVertices; i++) {
        	if (model.vertices[i].y != 0) { // not sea level?
        		return false;
        	}
        }
		return layer == 0; // upper layers can all be at "sea level"
	}
	
	public List<Triangle> triangulateFace(Model model, int faceIdx, Vector3f[] normals) {
		List<Triangle> tris = new ArrayList<>();
		
		// TODO: dumb constructor
		tris.add(new Triangle(model, faceIdx, false, normals));
		if (model.numVerticesPerFace[faceIdx] != 3) {
			tris.add(new Triangle(model, faceIdx, true, normals));
    	}
		
		return tris;
	}
	
	public FileWriter pickBodySmd(FileWriter[][] writers, Triangle t) {
		float minX = 99999;
		float minZ = 99999;
		for (int i = 0; i < t.verts.length; i++) {
			if (t.verts[i].x < minX) {
				minX = t.verts[i].x;
			}
			if (t.verts[i].y < minZ) {
				minZ = t.verts[i].y;
			}
		}
		
		int subSectorSize = (32*96) / SUB_SECTOR_COUNT;
		int subX = Math.max( Math.min((int)minX / subSectorSize, SUB_SECTOR_COUNT-1), 0);
		int subZ = Math.max( Math.min((int)minZ / subSectorSize, SUB_SECTOR_COUNT-1), 0);
		System.out.println("PICKING SMD " + subX + " " + subZ);
		return writers[subX][subZ];
	}
	
	public void exportTerrain(Model model, int[][] landscapeHeights, Vector3f[] terrainNormals, 
			Vector3f[] bridgeNormals, FileWriter terrainSmd, FileWriter floorSmd, FileWriter[][] smdBodies) throws IOException {        	
		
        if (isEmpty(model)) {
        	//System.out.println("Skipping empty layer " + layer);
        	return;
        }
        
        if (layer == 0 || layer == 3) // layers 1-2 are building floors only
        	writeTerrainTexture();
    	
    	String mdl_name = smdPrefix;
    	String sectorTexName = mdl_name + ".bmp";
    	
    	List<Triangle> landscapeTris = new ArrayList<>(); // trianglulated faces
		
		// triangulate quads and strip special faces
		for (int i = 0; i < model.numFaces; i++) {
			if (model.numVerticesPerFace[i] == 0) {
				continue;
			}
			boolean isRoad = model.faceFillBack[i] == -16913;
			boolean isBridge = model.faceFillBack[i] == 0xbc614e;
			boolean isWater = model.faceFillBack[i] == 1;
			boolean isLava = model.faceFillBack[i] == 31;
			boolean isBuildingFloor = model.faceFillBack[i] == 3;
			boolean isMountainSide = model.faceFillBack[i] == -26426;
			boolean isCarpet = model.faceFillBack[i] == -27685;
			boolean isStar = model.faceFillBack[i] == 32;
			
			if (superModelMode && (isWater || isLava)) {
				boolean isZeroHeightWater = true;
				for (int k = 0; k < model.numVerticesPerFace[i]; k++) {
					Vector3i wall_vert = model.vertices[model.faceVertices[i][k]];
					if (wall_vert.y != 0) {
						isZeroHeightWater = false;
						break;
					}
				}
				if (isZeroHeightWater) {
					continue;
				}
			}
			
			List<Triangle> tris = triangulateFace(model, i, isBridge ? bridgeNormals : terrainNormals);
			
			if (isBuildingFloor || isBridge || isRoad || isWater || isLava || isMountainSide || isCarpet || isStar) {
				for (Triangle tri : tris) {
					tri.applyTopDownUV(false);
				}
			}
			
			if (layer == 3) {
				for (Triangle tri : tris) {
					for (int k = 0; k < 3; k++) {
						tri.verts[k].z -= UNDERGROUND_DEPTH;
					}
				}
			}
			else if (layer > 0) {
				for (Triangle tri : tris) {
					for (int k = 0; k < 3; k++) {
						tri.verts[k].z += getLandscapeHeight(landscapeHeights, tri.verts[k]);
					}
				}
			}			
			
			for (Triangle tri : tris) {
				if (isRoad) {
					tri.texture = "road.bmp";
	    			writeSolidColorTexture(tri.texture, DataUtils.rscColorToRgbColor(-16913));
				}
				else if (isMountainSide) {
					tri.texture = "cliff.bmp";
	    			writeSolidColorTexture(tri.texture, DataUtils.rscColorToRgbColor(-26426));
				}
				else if (isCarpet) {
					tri.texture = "carpet.bmp";
	    			writeSolidColorTexture(tri.texture, DataUtils.rscColorToRgbColor(-27685));
				}
				else if (isWater) {
					tri.texture = "water.bmp";
					writeTexture(Resources.textures[1], tri.texture);
				}
				else if (isLava) {
					tri.texture = "lava.bmp";
					writeTexture(Resources.textures[31], tri.texture);
				}
				else if (isBridge || isBuildingFloor) {
					tri.texture = "floor_3.bmp";
					writeTexture(Resources.textures[3], tri.texture);
				}
				else if (isStar) {
					tri.texture = "star.bmp";
					writeTexture(Resources.textures[32], tri.texture);
				}
				else
					tri.texture = sectorTexName;
			}
			
			if (model.faceFillBack[i] == 0xbc614e && !superModelMode) {
				List<Triangle> newTri = new ArrayList<>();
				for (Triangle tri : tris) {
					newTri.add(tri.flip());
				}
				tris = newTri;
			}
			
			landscapeTris.addAll(tris);
			
			if (isBuildingFloor || isBridge || isRoad || isCarpet) {
				if (isBridge || (layer > 0 && layer != 3)) {
					for (Triangle tri : tris) {
						landscapeTris.add(tri.flip()); // underside of second/third story floors
					}
				}
			}				
		}
		
		if (landscapeTris.isEmpty())
			return;
		
		// sectors have a little more than 4096 tris, so multiple body groups are needed
		// to get a goldsource mdl to compile.
		int bodyParts = (layer == 0 || layer == 3) ? 2 : 1;
		
		if (superModelMode || true) {
			bodyParts = 1; // don't need to compile these smds
		}
		
		for (int b = 0; b < bodyParts; b++) {
			String smd_path = mdl_name + "_b" + b + ".smd";
			
			int len = landscapeTris.size()/bodyParts;
			int offset = b * len;
			
			FileWriter smd = null;
			if (terrainSmd != null) {
				smd = terrainSmd;
			}
    		
    		for (int i = offset; i < offset + len; i++) {
    			boolean superModelFloor = superModelMode && layer != 0 && layer != 3;
    			
    			if (terrainSmd == null)
    				smd = pickBodySmd(smdBodies, landscapeTris.get(i));
    			
    			if (superModelFloor)
    				floorSmd.write(landscapeTris.get(i).texture + "\n");
    			else
    				smd.write(landscapeTris.get(i).texture + "\n");
            	for (int k = 0; k < 3; k++) {
            		Vertex vert = landscapeTris.get(i).verts[k];
	            	
            		if (landscapeTris.get(i).texture.equals(sectorTexName)) {
		            	float u = (vert.x) / (64.0f*96.0f);
		            	float v = (vert.y) / (64.0f*96.0f);
		            	
		            	// account for 1px border
		            	vert.u = 1.0f - (u*(48.0f/50.0f) - 0*(1.0f/50.0f));
		            	vert.v = 1.0f - (v*(48.0f/50.0f) - 0*(1.0f/50.0f));
            		}
            		
            		if (superModelFloor)
            			floorSmd.write(vert.toSmdString());
            		else
            			smd.write(vert.toSmdString());
            	}
            }
    		
    		// draw underground roof (giant black quad at sea level)
    		if (b == 1 && !superModelMode) {
    			
    			writeSolidColorTexture("black.bmp", 0);
    			
    			float sz = SECTOR_SIZE*128;
    			Vector3f normal = new Vector3f(0,0,-1);
    			Vertex v0 = new Vertex(new Vector3f(0, 0, 0), normal, new Vector2f(0, 0));
    			Vertex v1 = new Vertex(new Vector3f(sz, 0, 0), normal, new Vector2f(1, 0));
    			Vertex v2 = new Vertex(new Vector3f(sz, 0, sz), normal, new Vector2f(1, 1));
    			Vertex v3 = new Vertex(new Vector3f(0, 0, sz), normal, new Vector2f(0, 1));
    			
    			smd.write("black.bmp\n");
    			smd.write(v2.toSmdString());
    			smd.write(v1.toSmdString());
    			smd.write(v0.toSmdString());
    			
    			smd.write("black.bmp\n");
    			smd.write(v3.toSmdString());
    			smd.write(v2.toSmdString());
    			smd.write(v0.toSmdString());
    		}
		}
	}

	public void exportWalls(Model model, int[][] landscapeHeights, FileWriter bigSmd, FileWriter[][] smdBodies) throws IOException {
        if (isEmpty(model))
        	return;
    	
        String smd_path = smdPrefix + "_b2.smd";
        
		FileWriter smd = null;
		
		if (bigSmd != null) {	
			smd = bigSmd;
		}
		
    	
		// triangulate quads
		for (int i = 0; i < model.numFaces; i++) {
			if (model.numVerticesPerFace[i] == 0) {
				continue;
			}
			// skip doorways (not sure why there are tris for this anyway
			if (model.faceFillFront[i] == Model.USE_GOURAUD_LIGHTING) {
				continue;
			}
			
			Vertex[] verts = new Vertex[4];
        	for (int k = 0; k < 4; k++) {
        		int idx = model.faceVertices[i][k];
        		Vector3i vert = model.vertices[idx];       	
            	verts[k] = new Vertex(vert.x, vert.y, vert.z);
            	
            	if (layer > 0) {
            		if (layer == 3) {
            			verts[k].z -= UNDERGROUND_DEPTH;
            		} else {
            			verts[k].z += getLandscapeHeight(landscapeHeights, verts[k]);
            		}
            	}
        	}
			
        	// flat shaded normal
        	Vector3f ba = verts[1].getPos().sub(verts[0].getPos());
        	Vector3f ca = verts[2].getPos().sub(verts[0].getPos());
        	Vector3f normal = ba.cross(ca).normalize();
        	
        	for (int k = 0; k < 4; k++) {
        		verts[k].nx = normal.x;
        		verts[k].ny = normal.y;
        		verts[k].nz = normal.z;
        	}
        	
        	// get UV coordinates
        	Vector3f centroid = verts[0].getPos()
        		.add(verts[1].getPos())
        		.add(verts[2].getPos())
        		.div(3.0f);
        	Vector3f axisU = new Vector3f(normal).cross(new Vector3f(0,0,1)).normalize();
        	
        	float a = axisU.x;
        	float b = axisU.y;
        	float c = axisU.z;
        	float d = a*centroid.x + b*centroid.y + c*centroid.z;
        	
        	float lowestRight = 99999.0f;
        	float lowestLeft = 99999.0f;
        	int lowestRightIdx = -1;
        	int lowestLeftIdx = -1;
        	for (int k = 0; k < 4; k++) {
        		float x = verts[k].x;
            	float y = verts[k].y;
            	float z = verts[k].z;
        		boolean rightSide = a*x + b*y + c*z - d > 0;
        		
        		if (rightSide && z < lowestRight) {
        			lowestRight = z;
        			lowestRightIdx = k;
        		} else if (!rightSide && z < lowestLeft) {
        			lowestLeft = z;
        			lowestLeftIdx = k;
        		}
        	}
        	
        	for (int k = 0; k < 4; k++) {
        		float x = verts[k].x;
            	float y = verts[k].y;
            	float z = verts[k].z;
        		boolean rightSide = a*x + b*y + c*z - d > 0;
        		
        		float u = 0;
        		float v = 0;
        		if (rightSide) {
        			u = 1;
        			v = k == lowestRightIdx ? 0 : 1;
        		} else {
        			u = 0;
        			v = k == lowestLeftIdx ? 0 : 1;
        		}
        		verts[k].u = u;
        		verts[k].v = v;
        	}
        	
        	List<Triangle> tris = new ArrayList<>(); // triangulated faces
        	int faceSides = superModelMode ? 1 : 2;
			for (int faceSide = 0; faceSide < faceSides; faceSide++) {
				int fill = faceSide == 0 ? model.faceFillFront[i] : model.faceFillBack[i];
    			
				Texture tex = Resources.textures[fill];
    			String bmp_name = "wall_" + fill + ".bmp";
    			
    			writeTexture(tex, bmp_name);
    			
				List<Triangle> newTri = new ArrayList<>();
				newTri.add(new Triangle(verts[2], verts[1], verts[0], bmp_name));
				newTri.add(new Triangle(verts[3], verts[2], verts[0], bmp_name));
				for (Triangle tri : newTri) {
					if (faceSide == 1) {
						for (int k = 0; k < 3; k++) {
							tri.verts[k].u = 1.0f - tri.verts[k].u;
						}
						tri = tri.flip();
					}
					tris.add(tri);
        		}
			}
			
			for (int t = 0; t < tris.size(); t++) {
				if (bigSmd == null)
    				smd = pickBodySmd(smdBodies, tris.get(t));
				
    			smd.write(tris.get(t).texture + "\n");
    			
            	for (int k = 0; k < 3; k++) {
            		Vertex vert = tris.get(t).verts[k];
            		smd.write(vert.toSmdString());
            	}
    		}
		}
	}

	public void exportRoofs(Model model, int[][] landscapeHeights, List<Boolean> roofFlags, 
			Vector3f[] normals, FileWriter bigSmd, FileWriter[][] smdBodies) throws IOException {
		if (isEmpty(model))
        	return;
	
    	//
    	// write model
    	//
    	List<Triangle> roofTris = new ArrayList<>(); // triangulated faces

    	String smd_path = smdPrefix + "_b3.smd";
		
		FileWriter smd = null;
		if (bigSmd != null) {
			smd = bigSmd;
		}

		for (int i = 0; i < model.numFaces; i++) {
			if (model.numVerticesPerFace[i] == 0) {
				continue;
			}
			int fill = model.faceFillFront[i];
			Texture tex = Resources.textures[fill];
			String bmp_name = "floor_" + fill + ".bmp";
			
			writeTexture(tex, bmp_name);
			
    		// mostly a guess with some checking. There's like 7 scenarios to
    		// flip or not and it looks random and shitty in the real game anyway.
    		// Also angled roofs should use face-aligned UV axis, not world.
    		boolean shouldFlip = roofFlags.get(i);
    		
    		List<Triangle> tris = triangulateFace(model, i, normals);
			for (Triangle tri : tris) {
				tri.applyTopDownUV(shouldFlip);
				tri.texture = bmp_name;
				if (layer > 0) {
					for (int k = 0; k < 3; k++) {
						if (layer == 3) {
							tri.verts[k].z -= UNDERGROUND_DEPTH;
						} else {
							tri.verts[k].z += getLandscapeHeight(landscapeHeights, tri.verts[k]);
						}
					}
				}
				if (!superModelMode)
					roofTris.add(tri.flip());
			}
			roofTris.addAll(tris);
		}
		
		for (int i = 0; i < roofTris.size(); i++) {
			if (bigSmd == null)
				smd = pickBodySmd(smdBodies, roofTris.get(i));
			
        	smd.write(roofTris.get(i).texture + "\n");
        	for (int k = 0; k < 3; k++) {        		
        		Vertex vert = roofTris.get(i).verts[k];
        		smd.write(vert.toSmdString());
        	}
        }
	}
}
