package net.osmand.data.diff;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import gnu.trove.map.hash.TLongObjectHashMap;
import net.osmand.binary.BinaryInspector;
import net.osmand.binary.BinaryMapDataObject;
import net.osmand.binary.BinaryMapIndexReader.MapIndex;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.MapZooms.MapZoomPair;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.Amenity;
import rtree.RTreeException;

public class ObfDiffGenerator {
	private static final int COORDINATES_PRECISION_COMPARE = 0;
	
	private static final String OSMAND_CHANGE_VALUE = "delete";
	private static final String OSMAND_CHANGE_TAG = "osmand_change";
	public static final int BUFFER_SIZE = 1 << 20;
	
	public static void main(String[] args) throws IOException, RTreeException {
		if(args.length == 1 && args[0].equals("test")) {
			args = new String[3];
			args[0] = "/Users/victorshcherb/osmand/maps/diff/17_09_03_22_15_before.obf.gz";
			args[1] = "/Users/victorshcherb/osmand/maps/diff/17_09_03_22_15_after.obf.gz";
			args[2] = "/Users/victorshcherb/osmand/maps/diff/Diff.obf";
//			args[2] = "stdout";
		}
		if (args.length != 3) {
			System.out.println("Usage: <path to old obf> <path to new obf> <[result file name] or [stdout]>");
			System.exit(1);
			return;
		}
		try {
			ObfDiffGenerator generator = new ObfDiffGenerator();
			generator.run(args);
		} catch(Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
	}
	
	private void run(String[] args) throws IOException, RTreeException, SQLException {
		File start = new File(args[0]);
		File end = new File(args[1]);
		File result  = args.length < 3 || args[2].equals("stdout") ? null :new File(args[2]);
		if (!start.exists()) {
			System.err.println("Input Obf file doesn't exist: " + start.getAbsolutePath());
			System.exit(1);
			return;
		}
		if (!end.exists()) {
			System.err.println("Input Obf file doesn't exist: " + end.getAbsolutePath());
			System.exit(1);
			return;
		}
		generateDiff(start, end, result);
	}

	private void generateDiff(File start, File end, File result) throws IOException, RTreeException, SQLException {
		ObfFileInMemory fStart = new ObfFileInMemory();
		fStart.readObfFiles(Collections.singletonList(start));
		ObfFileInMemory fEnd = new ObfFileInMemory();
		fEnd.readObfFiles(Collections.singletonList(end));

		System.out.println("Comparing the files...");
		compareMapData(fStart, fEnd, result == null);
		compareRouteData(fStart, fEnd, result == null);
		comparePOI(fStart, fEnd, result == null);
		// TODO Compare Transport
		//compareTransport(fStart, fEnd);
		
		System.out.println("Finished comparing.");
		if (result != null) {
			if (result.exists()) {
				result.delete();
			}
			fEnd.writeFile(result, false);
		}
	}
	
	private void comparePOI(ObfFileInMemory fStart, ObfFileInMemory fEnd, boolean print) {
	 	TLongObjectHashMap<Map<String, Amenity>> startPoiSource = fStart.getPoiObjects();
	 	TLongObjectHashMap<Map<String, Amenity>> endPoiSource = fEnd.getPoiObjects();
	 	if (endPoiSource == null) {
	 		return;
	 	}
	 	Map<String, Amenity> startPoi = buildPoiMap(startPoiSource);
	 	Map<String, Amenity> endPoi = buildPoiMap(endPoiSource);
	 	if(print) {
			System.out.println("Compare POI");
		}
		for (String idx : startPoi.keySet()) {
			Amenity objE = endPoi.get(idx);
			Amenity objS = startPoi.get(idx);
			if (print) {
				if (objE == null) {
					System.out.println("POI " + idx + " is missing in (2): " + objS);
				} else {
					if (!objS.comparePoi(objE)) {
						System.out.println("POI " + idx + " is not equal: " + objS + " != " + objE);
					}
					endPoi.remove(idx);
				}
			} else {
				if (objE == null) {
					objS.setAdditionalInfo(OSMAND_CHANGE_TAG, OSMAND_CHANGE_VALUE);
					endPoi.put(idx, objS);
					if (endPoiSource.get(objS.getId()) == null) {
						endPoiSource.put(objS.getId(), new TreeMap<String, Amenity>());
					}
					endPoiSource.get(objS.getId()).put(objS.getType().getKeyName(), objS);
				} else {
					if (objS.comparePoi(objE)) {
						endPoi.remove(idx);
						endPoiSource.get(objS.getId()).remove(objS.getType().getKeyName());
					}
				}
			}
		}
	 	
		if (print) {
			Iterator<Entry<String, Amenity>> it = endPoi.entrySet().iterator();
			while (it.hasNext()) {
				Entry<String, Amenity> e = it.next();
				System.out.println("POI " + e.getKey() + " is missing in (1): " + e.getValue());
			}
		}
	 	
	 }

	private Map<String, Amenity> buildPoiMap(TLongObjectHashMap<Map<String, Amenity>> startPoiSource) {
		HashMap<String, Amenity> map = new HashMap<>();
		for (Map<String, Amenity> am : startPoiSource.valueCollection()) {
			Iterator<Entry<String, Amenity>> it = am.entrySet().iterator();
			while (it.hasNext()) {
				Entry<String, Amenity> next = it.next();
				String key = next.getValue().getId() + ":" + next.getKey();
				map.put(key, next.getValue());
			}
		}
		return map;
	}


	private void compareMapData(ObfFileInMemory fStart, ObfFileInMemory fEnd, boolean print) {
		fStart.filterAllZoomsBelow(13);
		fEnd.filterAllZoomsBelow(13);
		MapIndex mi = fEnd.getMapIndex();
		int deleteId;
		Integer rl = mi.getRule(OSMAND_CHANGE_TAG, OSMAND_CHANGE_VALUE);
		if(rl != null) {
			deleteId = rl; 
		} else {
			deleteId = mi.decodingRules.size() + 1;
			mi.initMapEncodingRule(0, deleteId, OSMAND_CHANGE_TAG, OSMAND_CHANGE_VALUE);
		}
		for (MapZoomPair mz : fStart.getZooms()) {
			TLongObjectHashMap<BinaryMapDataObject> startData = fStart.get(mz);
			TLongObjectHashMap<BinaryMapDataObject> endData = fEnd.get(mz);
			if(print) {
				System.out.println("Compare map " + mz);
			}
			if (endData == null) {
				continue;
			}
			for (Long idx : startData.keys()) {
				BinaryMapDataObject objE = endData.get(idx);
				BinaryMapDataObject objS = startData.get(idx);
				if (print) {
					if (objE == null) {
						System.out.println("Map " + idx + " is missing in (2): " + toString(objS));
					} else {
						if (//!objS.getMapIndex().decodeType(objS.getTypes()[0]).tag.equals(OSMAND_CHANGE_TAG) &&
								!objE.compareBinary(objS, COORDINATES_PRECISION_COMPARE )) {
							System.out.println("Map " + idx + " is not equal: " + toString(objS) + " != " + toString(objE));
						}
						endData.remove(idx);
					}
				} else {
					if (objE == null) {
						// Object with this id is not present in the second obf
						BinaryMapDataObject obj = new BinaryMapDataObject(idx, objS.getCoordinates(), null,
								objS.getObjectType(), objS.isArea(), new int[] { deleteId }, null);
						endData.put(idx, obj);
					} else if (objE.compareBinary(objS, COORDINATES_PRECISION_COMPARE)) {
						endData.remove(idx);
					}
				}
			}
			if(print) {
				for (BinaryMapDataObject e : endData.valueCollection()) {
					System.out.println("Map " + e.getId() + " is missing in (1): " + toString(e));
				}
			}
		}

	}
	
	private String toString(BinaryMapDataObject objS) {
		StringBuilder s = new StringBuilder();
		BinaryInspector.printMapDetails(objS, s, false);
		return s.toString();
	}

	private void compareRouteData(ObfFileInMemory fStart, ObfFileInMemory fEnd, boolean print) {
		RouteRegion ri = fEnd.getRouteIndex();
		int deleteId = ri.searchRouteEncodingRule(OSMAND_CHANGE_TAG, OSMAND_CHANGE_VALUE);
		if (deleteId == -1) {
			deleteId = ri.routeEncodingRules.size();
			if(deleteId == 0) {
				deleteId = 1;
			}
			ri.initRouteEncodingRule(deleteId, OSMAND_CHANGE_TAG, OSMAND_CHANGE_VALUE);
		}

		TLongObjectHashMap<RouteDataObject> startData = fStart.getRoutingData();
		TLongObjectHashMap<RouteDataObject> endData = fEnd.getRoutingData();
		if (endData == null) {
			return;
		}
		for (Long idx : startData.keys()) {
			RouteDataObject objE = endData.get(idx);
			RouteDataObject objS = startData.get(idx);
			if (print) {
				if (objE == null) {
					System.out.println("Route " + idx + " is missing in (2): " + objS);
				} else {
					if (!objE.compareRoute(objS)) {
						System.out.println("Route " + idx + " is not equal: " + objS + " != " + objE);
					}
					endData.remove(idx);
				}
			} else {
				if (objE == null) {
					// Object with this id is not present in the second obf
					RouteDataObject rdo = new RouteDataObject(ri);
					rdo.id = objS.id;
					rdo.pointsX = objS.pointsX;
					rdo.pointsY = objS.pointsY;
					rdo.types = new int[] { deleteId };
					endData.put(idx, rdo);
				} else if (objE.compareRoute(objS)) {
					endData.remove(idx);
				}
			}
		}
		if(print) {
			for(RouteDataObject e: endData.valueCollection()) {
				System.out.println("Route " + e.getId() + " is missing in (1): " + e);
			}
		}
	}

	
}
