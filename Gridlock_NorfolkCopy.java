/**
 ** Gridlock.java
 **
 ** Copyright 2011 by Sarah Wise, Mark Coletti, Andrew Crooks, and
 ** George Mason University.
 **
 ** Licensed under the Academic Free License version 3.0
 **
 ** See the file "LICENSE" for more information
 **
 * $Id: Gridlock.java 849 2013-01-08 22:56:52Z mcoletti $
 * 
 **/
package sim.app.geo.gridlock_norfolk_copy;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.planargraph.Node;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import sim.app.geo.colorworld.Agent;
import sim.app.geo.norfolk.Norfolk;
import sim.engine.SimState;
import sim.engine.Steppable;
import sim.field.geo.GeomVectorField;
import sim.io.geo.ShapeFileImporter;
import sim.util.Bag;
import sim.util.geo.GeomPlanarGraph;
import sim.util.geo.GeomPlanarGraphEdge;
import sim.util.geo.MasonGeometry;

/**
 * The  simulation core.
 * 
 * The simulation can require a LOT of memory, so make sure the virtual machine has enough.
 * Do this by adding the following to the command line, or by setting up your run 
 * configuration in Eclipse to include the VM argument:
 * 
 * 		-Xmx2048M
 * 
 * With smaller simulations this chunk of memory is obviously not necessary. You can 
 * take it down to -Xmx800M or some such. If you get an OutOfMemory error, push it up.
 */
public class Gridlock_NorfolkCopy extends SimState
{
    private static final long serialVersionUID = 1L;
    
    public GeomVectorField roads = new GeomVectorField();
    public GeomVectorField censusTracts = new GeomVectorField();

    // traversable network
    public GeomPlanarGraph network = new GeomPlanarGraph();

    public GeomVectorField junctions = new GeomVectorField();

    // mapping between unique edge IDs and edge structures themselves
    HashMap<Integer, GeomPlanarGraphEdge> idsToEdges =
        new HashMap<Integer, GeomPlanarGraphEdge>();

    HashMap<GeomPlanarGraphEdge, ArrayList<AgentCopy>> edgeTraffic =
        new HashMap<GeomPlanarGraphEdge, ArrayList<AgentCopy>>();

    public GeomVectorField agents = new GeomVectorField();

    ArrayList<AgentCopy> agentList = new ArrayList<AgentCopy>();
    
    // system parameter: can force agents to go to or from work at any time
    boolean goToWork = true;



    public boolean getGoToWork()
    {
        return goToWork;
    }



    public void setGoToWork(boolean val)
    {
        goToWork = val;
    }

    // cheap, hacky, hard-coded way to identify which edges are associated with
    // goal Nodes. Done because we cannot seem to read in .shp file for goal nodes because
    // of an NegativeArraySize error? Any suggestions very welcome!
    
    // Use the ID-ID codes from the .CSV and the roads.shp
    Integer[] goals =
    {
    		//72142, 72176, 72235, 72178, 89178
    		//21284, 21948, 21109, 21971
    		//10, 100, 200, 300, 400
    		21399, 7842, 21368, 21736
    };



    /** Constructor */
    public Gridlock_NorfolkCopy(long seed)
    {
        super(seed);
    }



    /** Initialization */
    @Override
    public void start()
    {
        super.start();

        // read in data
        try
        {
            // read in the roads to create the transit network
            System.out.println("reading roads layer...");
            
            URL roadsFile = Gridlock_NorfolkCopy.class.getResource("data/roads.shp");
            
            ShapeFileImporter.read(roadsFile, roads);

            Envelope MBR = roads.getMBR();

            // read in the tracts to create the background
            System.out.println("reading tracts layer...");
            
            URL areasFile = Gridlock_NorfolkCopy.class.getResource("data/areas.shp");
            ShapeFileImporter.read(areasFile, censusTracts);


            MBR.expandToInclude(censusTracts.getMBR());
            System.out.println("finished reading layers...");
            
            createNetwork();

            // update so that everyone knows what the standard MBR is
            roads.setMBR(MBR);
            censusTracts.setMBR(MBR);
            

            // initialize agents
            //populate("data/merge3.csv");
            populate("/Users/KJGarbutt/Copy/workspace/abmtest/src/sim/app/geo/gridlock_norfolk_copy/data/areas_roads_merge1.csv");
            agents.setMBR(MBR);

            // Ensure that the spatial index is updated after all the agents
            // move
            schedule.scheduleRepeating( agents.scheduleSpatialIndexUpdater(), Integer.MAX_VALUE, 1.0);

            /** Steppable that flips Agent paths once everyone reaches their destinations*/
            Steppable flipper = new Steppable()
            {
                /* (non-Javadoc)
                 * @see sim.engine.Steppable#step(sim.engine.SimState)
                 */
                @Override
                public void step(SimState state)
                {

                    Gridlock_NorfolkCopy gstate = (Gridlock_NorfolkCopy) state;

                    // pass to check if anyone has not yet reached work
                    for (AgentCopy a : gstate.agentList)
                    {
                        if (!a.reachedDestination)
                        {
                            return; // someone is still moving: let him do so
                        }
                    }
                    // send everyone back in the opposite direction now
                    boolean toWork = gstate.goToWork;
                    gstate.goToWork = !toWork;

                    // otherwise everyone has reached their latest destination:
                    // turn them back
                    for (AgentCopy a : gstate.agentList)
                    {
                        a.flipPath();
                    }
                }
            };
            schedule.scheduleRepeating(flipper, 10);

        } catch (FileNotFoundException e)
        {
            System.out.println("Error: missing required data file");
        }
    }



    /** Create the road network the agents will traverse
     *
     */
    private void createNetwork()
    {
        System.out.println("creating network...");

        network.createFromGeomField(roads);
    
        for (Object o : network.getEdges())
        {
            GeomPlanarGraphEdge e = (GeomPlanarGraphEdge) o;

            idsToEdges.put(e.getIntegerAttribute("ID_ID").intValue(), e);

            e.setData(new ArrayList<Agent>());
        }
    
        addIntersectionNodes(network.nodeIterator(), junctions);
        System.out.println("finished creating network...");
    }
    
    
    
    /**
     * Read in the population file and create an appropriate pop
     * @param filename
     */
    public void populate(String filename)	{
    	System.out.println("populating model...");
    	try	{
        	// filename = roads_points_place.csv?
            String filePath = Gridlock_NorfolkCopy.class.getResource(filename).getPath();
            // filePath = data/roads_points_place.csv?
            FileInputStream fstream = new FileInputStream(filePath);

            BufferedReader d = new BufferedReader(new InputStreamReader(fstream));
            String s;

            // get rid of the header
            d.readLine();
            // read in all data
            while ((s = d.readLine()) != null)	{ 
                String[] bits = s.split(",");
                // column 'L' TRACTTOTRA?
                //Integer pop = Integer.parseInt(bits[11]); // TODO: reset me if desired!
                // column 'M' TRACTTOTRA
                Integer pop = Integer.parseInt(bits[12]); // TODO: reset me if desired!
                
                // column 'F' SCTRACTW?
                //String workTract = bits[5];
                // column 'W' SCTRACTW
                String workTract = bits[22];
                
                // column 'I' SCTRACTR?
                //String homeTract = bits[8];
                // column 'X' SCTRACTR
                String homeTract = bits[23];
                
                // column 'N' ID_ID
                //String id_id = bits[13];
                // column 'V' Road_ID
                String id_id = bits[21];
                
                GeomPlanarGraphEdge startingEdge =
                    idsToEdges.get(
                    (Integer) Integer.parseInt(id_id));
                GeomPlanarGraphEdge goalEdge = idsToEdges.get(
                    goals[ random.nextInt(goals.length)]);
                for (int i = 0; i < 1; i++)	{
                	//pop; i++){
                    AgentCopy a = new AgentCopy(this, homeTract, workTract, startingEdge, goalEdge);

                    boolean successfulStart = a.start(this);

                    if (!successfulStart)	{
                        continue; // DON'T ADD IT if it's bad
                    }

                    // MasonGeometry newGeometry = new MasonGeometry(a.getGeometry());
                    MasonGeometry newGeometry = a.getGeometry();
                    newGeometry.isMovable = true;
                    agents.addGeometry(newGeometry);
                    agentList.add(a);
                    schedule.scheduleRepeating(a);
                }
            }
            System.out.println("finished populating model...");
            // clean up
            d.close();

        } catch (Exception e)
        {
            System.out.println("ERROR: issue with population file: " + e);
        }

    }

    /** adds nodes corresponding to road intersections to GeomVectorField
     *
     * @param nodeIterator Points to first node
     * @param intersections GeomVectorField containing intersection geometry
     *
     * Nodes will belong to a planar graph populated from LineString network.
     */
    private void addIntersectionNodes(Iterator<?> nodeIterator,
                                      GeomVectorField intersections)
    {
        GeometryFactory fact = new GeometryFactory();
        Coordinate coord = null;
        Point point = null;
        int counter = 0;

        while (nodeIterator.hasNext())	{
            Node node = (Node) nodeIterator.next();
            coord = node.getCoordinate();
            point = fact.createPoint(coord);

            junctions.addGeometry(new MasonGeometry(point));
            counter++;
            
        }
    }



    /** Main function allows simulation to be run in stand-alone, non-GUI mode */
    public static void main(String[] args)
    {
        doLoop(Gridlock_NorfolkCopy.class, args);
        System.exit(0);
    }

}