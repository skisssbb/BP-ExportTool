package bp17.jerseyserver.service;

import bp.common.model.WayBlacklist;
import bp.common.model.obstacles.*;
import bp.common.model.ways.Node;
import bp.common.model.ways.Way;
import bp17.jerseyserver.exceptions.SequenceIDNotFoundException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.jaxb.SourceType;
import org.postgresql.util.HStoreConverter;

import javax.persistence.Query;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import javax.xml.crypto.Data;
import javax.xml.transform.Result;
import java.io.Serializable;
import java.sql.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Date;

/**
 * This class handles all the export tool related tasks
 * Retrieve POJOs from hibernatedb
 * adjust these information and then add them to osm DB
 * finally export the osm database into an osm file
 * Created by Bi on 22.08.17.
 */
public class ExportTool {
    private long nextPossibleNodeId;
    private long nextPossibleWayId;
    private static volatile ExportTool instance;
    private Connection c;
    private Connection hibernate_con;
    private String current_time;
    private Timestamp current_timestamp;

    /*************************************************************************************
     *
     * SQL Section
     *
     *************************************************************************************/
    PreparedStatement pstmt_selectLatitudeFromNode = null;
    PreparedStatement pstmt_selectLongitudeFromNode = null;
    PreparedStatement pstmt_checkIfOSMIDExistInNode = null;
    String sql_selectLatitudeFromNode = "SELECT ST_Y(geom) AS latitude FROM nodes WHERE id = ?;";
    String sql_selectLongitudeFromNode = "SELECT ST_X(geom) AS longitude FROM nodes WHERE id = ?;";
    String sql_checkIfOSMIDExistInNode =  "SELECT id FROM nodes WHERE id = ?;";

    /*************************************************************************************
     *
     * SQL Section
     *
     *************************************************************************************/

    /**
     * Constructor sets global c, formated current_time and nextPossibleNodeId
     */
    public ExportTool(){
        this.c = PostgreSQLJDBC.getInstance().getConnection();
        LocalDateTime tmp = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        this.current_time = tmp.format(formatter);
        Calendar calendar = Calendar.getInstance();
        Date now = calendar.getTime();
        current_timestamp = new Timestamp(now.getTime());
        try {
            Class.forName("org.postgresql.Driver");
            hibernate_con = DriverManager
                    .getConnection("jdbc:postgresql://localhost:5432/hibernatedb","postgres","password");
            hibernate_con.setAutoCommit(false);
            System.out.println("Hibernate Connected.");

            Statement stmt1 = c.createStatement();
            ResultSet rs = stmt1.executeQuery("SELECT max(id) FROM nodes");
            rs.next();
            this.nextPossibleNodeId = rs.getLong("max")+1 ;
            rs.close();
            stmt1.close();

            Statement stmt2 = c.createStatement();
            ResultSet rs2 = stmt2.executeQuery("SELECT max(id) FROM ways");
            rs2.next();
            this.nextPossibleWayId = rs2.getLong("max")+1 ;
            rs2.close();
            stmt2.close();
        } catch (SQLException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        try {
            pstmt_selectLatitudeFromNode = c.prepareStatement(sql_selectLatitudeFromNode);
            pstmt_selectLongitudeFromNode= c.prepareStatement(sql_selectLongitudeFromNode);
            pstmt_checkIfOSMIDExistInNode = c.prepareStatement(sql_checkIfOSMIDExistInNode);
        } catch (SQLException e) {
            e.printStackTrace();
        }

    }
    public static ExportTool getInstance(){
        if(instance == null){
            instance = new ExportTool();
        }
        return instance;
    }

    /**
     * return a list of retrieved data in form of a list of Objects
     * @param typeParameterClass
     * @param <T>
     * @return
     */
    public static <T> List<T> getDataAsList(Class<T> typeParameterClass){
        // Session Factory is created only once in the life span of the application. Get it from the Singleton
        SessionFactory sessionFactory = DatabaseSessionManager.instance().getSessionFactory();

        Session session = sessionFactory.openSession();

        CriteriaBuilder builder = session.getCriteriaBuilder();
        CriteriaQuery<T> criteria = builder.createQuery(typeParameterClass);
        Root<T> root = criteria.from(typeParameterClass);
        criteria.select(root);
        List<T> datalist = session.createQuery(criteria).getResultList();
        session.close();
        return datalist;
    }

    /**
     * the first method from Exporttool to be executed
     */
    public void startExportProcess(){
        System.out.println("WRITE WAY ------------------------------------------------");
        writeWaysInOSMDatabase();
        System.out.println("WRITE WAY done ------------------------------------------------");
        writeObstaclesInOsmDatabase();
        System.out.println("WRITE Obstacle done ------------------------------------------------");
        closeUpAllConnections();
    }

    /**
     * write all the Obstacle Objects retrieved from hibernatedb in osm DB
     * at first write all One Node Obstacle
     * then all the 2 nodes Obstacles
     */
    private void writeObstaclesInOsmDatabase(){
        //TODO check if insert Obstacle working, not tested yet after small changes
        List<Obstacle> obstacleList = getAllObstacles();
        if(obstacleList.isEmpty()) return;
        System.out.println("Number of Obstacles to update: " + obstacleList.size());
        List<Obstacle> oneNodeObstacleList = new ArrayList<Obstacle>();
        List<Obstacle> twoNodesObstacleList = new ArrayList<Obstacle>();
        for(Obstacle o:obstacleList){
            if(o.getLatitudeEnd() == 0 && o.getLongitudeEnd() == 0) oneNodeObstacleList.add(o);
            else twoNodesObstacleList.add(o);
        }
        // Its important to write OneNodeObstacle first
        System.out.println("WRITING ONE NODE OBS --------------------------------------------------------");
        writeOneNodeObstaclesInOsmDatabase(oneNodeObstacleList);
        System.out.println("WRITING TWO NODE OBS --------------------------------------------------------");
        writeTwoNodeObstaclesInOsmDatabase(twoNodesObstacleList);
        updateAlreadyExportedObstacle(obstacleList);
    }


    /**
     * @return a linked list of all Obstacle from hibernatedb
     */
    private List<Obstacle> getAllObstacles(){
        List<Obstacle> datalist = ExportTool.getDataAsList(Obstacle.class);

        //TODO remove println
        System.out.println("Number of Obstacles before check:"+datalist.size());
        Iterator<Obstacle> iter = datalist.iterator();
        while(iter.hasNext()){
            Obstacle o = iter.next();
            if(o.isAlreadyExported()) iter.remove();
        }

        System.out.println("Number of Obstacles after check:"+datalist.size());
        return datalist;
    }

    private void writeOneNodeObstaclesInOsmDatabase(List<Obstacle> obstacleList) {
        try {
            PreparedStatement updateNodes = null;
            PreparedStatement updateWays = null;
            PreparedStatement updateWay_Nodes = null;
            PreparedStatement updateWay_Nodes2 = null;
            PreparedStatement insertWay_Nodes = null;
            PreparedStatement getSequenceId = null;

            String sql_updateNodes = "INSERT INTO nodes (id, version, user_id, tstamp, changeset_id, tags, geom) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326));";
            String sql_updateWays = "UPDATE ways SET nodes = ? WHERE id = ?;";
            String sql_updateWay_Nodes = "UPDATE way_nodes SET sequence_id = (sequence_id + 1)*(-1) "+
                    "WHERE sequence_id >= ? AND way_id = ?;";
            String sql_updateWay_Nodes2 = "UPDATE way_nodes SET sequence_id = sequence_id*(-1) "+
                    "WHERE (sequence_id)*(-1) >= ? AND way_id = ?;";
            String sql_insertWay_Nodes ="INSERT INTO way_nodes (way_id, node_id, sequence_id) "
                    +"VALUES (?, ?, ?);";
            String sql_getSequenceId = "SELECT sequence_id FROM way_nodes WHERE way_id = ? AND node_id = ?;";

            updateNodes = c.prepareStatement(sql_updateNodes);
            updateWays = c.prepareStatement(sql_updateWays);
            updateWay_Nodes = c.prepareStatement(sql_updateWay_Nodes);
            updateWay_Nodes2 = c.prepareStatement(sql_updateWay_Nodes2);
            insertWay_Nodes = c.prepareStatement(sql_insertWay_Nodes);
            getSequenceId = c.prepareStatement(sql_getSequenceId);
            for(Obstacle o:obstacleList){
                insertObstacleInTableNode(o,updateNodes);
                updateTableWays(o,updateWays);
                updateTableWay_nodes(o,updateWay_Nodes, updateWay_Nodes2, insertWay_Nodes, getSequenceId);
            }
            updateNodes.close();
            updateWays.close();
            updateWay_Nodes.close();
            updateWay_Nodes2.close();
            insertWay_Nodes.close();
            getSequenceId.close();
            //c.commit();
            System.out.println("UPDATE OSM DB COMPLETED");
        } catch (SQLException e){
            e.printStackTrace();
            try {
                c.rollback();
            } catch (SQLException e1) {
                e1.printStackTrace();
            }
        }
    }

    /**
     * insert object o in node Table
     * @param o
     * @param pstmt a prepared statement: id, version, userID, tstamp, changesetID, tags, long and lat are to be filled in
     */
    private void insertObstacleInTableNode(Obstacle o, PreparedStatement pstmt) {
        long id = o.getOsm_id_start();
        int version = -1;
        int userID = -1;
        long changesetID = -1;
        String tags = getHStoreValue(o);
        try {
            pstmt.setLong(1, id);
            pstmt.setInt(2, version);
            pstmt.setInt(3, userID);
            pstmt.setTimestamp(4, current_timestamp);
            pstmt.setLong(5, changesetID);
            pstmt.setObject(6, tags,Types.OTHER);
            pstmt.setDouble(7, o.getLongitudeStart());
            pstmt.setDouble(8, o.getLatitudeStart());
            pstmt.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * update table ways for Obstacle Object o
     * to do is to insert the id of Obstacle o in the "nodes" entry of the suited way item
     * which is a array of NodeIDs
     * @param o the Obstacle Object to be inserted to the table
     * @param pstmt the prepared statement
     */
    private void updateTableWays(Obstacle o, PreparedStatement pstmt) {
        Statement stmt = null;
        try {
            stmt = c.createStatement();
            String sql = "SELECT nodes FROM ways WHERE id = "+"'"+String.valueOf(o.getId_way())+"';";
            ResultSet rs = stmt.executeQuery(sql);
            System.out.println("SQL: "+sql);
            if(rs.next()){
                System.out.println("Result Next True");
                Array nodes = rs.getArray(1);
                Long[] a_nodes = (Long[])nodes.getArray();
                Long[] result = insertNodeInArray(a_nodes, o);
                Array result_nodes = c.createArrayOf("BIGINT", result);
                pstmt.setArray(1,result_nodes);
                pstmt.setLong(2, o.getId_way());
                pstmt.execute();
            }
            rs.close();
            stmt.close();

        } catch (SQLException e) {
            e.printStackTrace();
            System.out.println("HERE:"+o.getId_way());
        }
    }

    /**
     * help method for updateTableWays
     * @param array
     * @param o
     * @return new array list of ID, with ID of o at the right posision
     */
    private Long[] insertNodeInArray(Long[] array, Obstacle o){
        Long[] tmp = new Long[array.length + 1];
        ArrayList<Long> list_nodes = new ArrayList<>();
        for(Long n:array){
            list_nodes.add(n);
        }
        list_nodes.add(list_nodes.indexOf(o.getId_firstnode()) + 1, o.getOsm_id_start());
        return list_nodes.toArray(tmp);
    }


    /**
     * Update Table way_nodes. Important is to update the sequence_id of each affected item
     * @param o the Obstacle Object
     * @param updatestmt Prepare Update Statement
     * @param updatestmt2 second part of Prepare Statement
     * @param insertstmt preparestatement to insert new instance in way_nodes table
     * @param getseqstmt Prepare SELECT Statement to retrieve sequence ID
     */
    private void updateTableWay_nodes(Obstacle o, PreparedStatement updatestmt, PreparedStatement updatestmt2,
                                      PreparedStatement insertstmt, PreparedStatement getseqstmt) {
        long sequence_id_firstNode;
        long way_id = o.getId_way();
        long obstacle_id = o.getOsm_id_start();
        long sequence_id_obstacle;
        try {
            getseqstmt.setLong(1, o.getId_way());
            getseqstmt.setLong(2, o.getId_firstnode());
            ResultSet rs = getseqstmt.executeQuery();
            System.out.println(getseqstmt);
            System.out.println(rs.isBeforeFirst());
            if(rs.next()){
                sequence_id_firstNode = rs.getInt("sequence_id");
                sequence_id_obstacle = sequence_id_firstNode + 1;
                System.out.println("Debug: SequenceID Obstacle:"+sequence_id_obstacle);
                updatestmt.setLong(1, sequence_id_obstacle);
                updatestmt.setLong(2, way_id);
                updatestmt2.setLong(1, sequence_id_obstacle);
                updatestmt2.setLong(2, way_id);
                insertstmt.setLong(1, way_id);
                insertstmt.setLong(2, obstacle_id);
                insertstmt.setLong(3, sequence_id_obstacle);
                updatestmt.execute();
                updatestmt2.execute();
                insertstmt.execute();
                rs.close();
            }
            else{
                rs.close();
                throw new SequenceIDNotFoundException();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } catch (SequenceIDNotFoundException e) {
            e.printStackTrace();
        }

    }

    /**
     * this method write Obstacle which consist of 2 Nodes in the OSM Database
     * @param obstacleList the list of the obstacles consisting of 2 nodes
     */
    private void writeTwoNodeObstaclesInOsmDatabase(List<Obstacle> obstacleList) {
        Way removedWayObject = null;
        PreparedStatement pstm_selectAWay = null;
        PreparedStatement pstm_removeWayFromWayNodes = null;
        PreparedStatement pstm_removeWayFromWay = null;

        String sql_selectAWay = "SELECT * FROM ways WHERE id = ?;";
        String sql_removeWayFromWayNodes = "DELETE FROM way_nodes WHERE way_id = ? ;";
        String sql_removeWayFromWay = "DELETE FROM ways WHERE id = ? ;";

        try {
            pstm_selectAWay = c.prepareStatement(sql_selectAWay);
            pstm_removeWayFromWayNodes = c.prepareStatement(sql_removeWayFromWayNodes);
            pstm_removeWayFromWay = c.prepareStatement(sql_removeWayFromWay);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        for(Obstacle ob:obstacleList){
            WayBlacklist wayToBeRemoved = removeWayFromOSMDB(ob, pstm_selectAWay, pstm_removeWayFromWayNodes, pstm_removeWayFromWay);
            removedWayObject = getWayObjectFromHibernateDB(ob);
            if(removedWayObject != null){
                removeWayFromHibernateDB(removedWayObject);
            }
            create3NewWaysAndSaveThem(ob, wayToBeRemoved, removedWayObject);
        }
    }

    /**
     * this method remove the way which the Obstacle o is on from OSM Database
     * meaning from way and way_nodes table
     * save its data in WaysBlacklist table
     * @param o the obstacle
     * @param pstm_selectAWay
     * @param pstm_removeWayFromWayNodes
     * @param pstm_removeWayFromWay
     */
    private WayBlacklist removeWayFromOSMDB(Obstacle o, PreparedStatement pstm_selectAWay,
                                    PreparedStatement pstm_removeWayFromWayNodes, PreparedStatement pstm_removeWayFromWay) {
        WayBlacklist wayToBeRemoved = giveWayToBeRemoved(o, pstm_selectAWay);
        postInTableWayBlacklist(wayToBeRemoved);
        if(wayToBeRemoved == null) return null;

        // Remove way from all osm tables
        try {
            // Remove way from way_nodes
            pstm_removeWayFromWayNodes.setLong(1,o.getId_way());
            pstm_removeWayFromWayNodes.executeUpdate();

            // Remove way from way
            pstm_removeWayFromWay.setLong(1,o.getId_way());
            pstm_removeWayFromWay.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return wayToBeRemoved;
    }

    /**
     *
     * @param o
     * @param pstm_selectAWay
     * @return give back the WayBlacklist Object representing the way to be removed
     */
    private WayBlacklist giveWayToBeRemoved(Obstacle o, PreparedStatement pstm_selectAWay) {
        long osm_id = 0;
        int version = 0;
        int user_id = 0;
        Timestamp tstamp = null;
        long changeset_id = 0;
        String tags = "";
        Array nodes = null;
        List<Long> nodes_list = null;
        try {
            pstm_selectAWay.setLong(1, o.getId_way());
            ResultSet rs = pstm_selectAWay.executeQuery();
            if(!rs.isBeforeFirst()) return null;
            while(rs.next()){
                osm_id = rs.getLong("id");
                version = rs.getInt("version");
                user_id = rs.getInt("user_id");
                tstamp = rs.getTimestamp("tstamp");
                changeset_id = rs.getLong("changeset_id");
                tags = HStoreConverter.toString((Map<String, String>) rs.getObject("tags"));
                nodes = rs.getArray("nodes");
                nodes_list = Arrays.asList((Long[]) nodes.getArray());
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return new WayBlacklist(osm_id,version,user_id,tstamp,changeset_id,tags,nodes_list);
    }

    /**
     * Post a WayBlacklist Object in HibernateDB
     * @param wayToBeRemoved
     */
    private void postInTableWayBlacklist(WayBlacklist wayToBeRemoved) {
        Session session = null;
        Transaction tx = null;
        try{
            SessionFactory sessionFactory = DatabaseSessionManager.instance().getSessionFactory();
            session = sessionFactory.openSession();
            tx = session.beginTransaction();
            session.save(wayToBeRemoved);
            tx.commit();
        }catch (Exception e){
            e.printStackTrace();
            tx.rollback();
        }finally {
            session.close();
        }
    }

    /**
     * Post a Way Object in HibernateDB
     * @param way
     */
    private void postInTableWayHibernate(Way way){
        Session session = null;
        Transaction tx = null;
        try{
            SessionFactory sessionFactory = DatabaseSessionManager.instance().getSessionFactory();
            session = sessionFactory.openSession();
            tx = session.beginTransaction();
            session.save(way);
            tx.commit();
        }catch (Exception e){
            e.printStackTrace();
            tx.rollback();
        }finally {
            session.close();
        }
    }

    /**
     *
     * @param ob
     * @return the node List for the way which ob is on
     */
    private Way getWayObjectFromHibernateDB(Obstacle ob) {
        // TESTED
        Session session =  DatabaseSessionManager.instance().getSessionFactory().openSession();
        CriteriaBuilder builder = session.getCriteriaBuilder();
        CriteriaQuery<Way> criteria = builder.createQuery(Way.class);
        Root<Way> root = criteria.from(Way.class);
        criteria.select(root);
        criteria.where(builder.equal(root.get("osm_id"), ob.getId_way()));
        List<Way> datalist = session.createQuery(criteria).getResultList();
        session.close();
        if(!datalist.isEmpty()) return datalist.get(0);
        return null;
    }

    /**
     * remove a way from HibernateDB Table way
     * @param way
     * @return a WayBlacklist Object refering to the removed way
     */
    private void removeWayFromHibernateDB(Way way) {
        List<Long> node_osmids_list = new ArrayList<Long>();
        for(Node n:way.getNodes()) node_osmids_list.add(n.getOsm_id());
        WayBlacklist result = new WayBlacklist(way.getOsm_id(), -1, -1,
                new Timestamp(System.currentTimeMillis()),-1, getHStoreValue(way),node_osmids_list);
        Session session = null;
        Transaction tx = null;

        try {
            session =  DatabaseSessionManager.instance().getSessionFactory().openSession();
            tx = session.beginTransaction();
            Serializable id = new Long(way.getId());
            Object persistentInstance = session.load(Way.class, id);
            if(persistentInstance != null){
                session.delete(persistentInstance);
            }
            tx.commit();

        }catch (Exception ex) {
            ex.printStackTrace();
            tx.rollback();
        }
        finally {session.close();}
    }

    /**
     * create 3 ways, one is from starting point of the removed way to the starting point of the stair
     * one is the stair itself
     * one is from the end point of the stair to the end point of the way
     * save these 3 in HibernateDB as well as OSM DB
     * @param ob the obstacle
     * @param wayToBeRemoved WayBlacklist Object which represents the deleted Way
     * @param removedWayObject the deleted Way Object
     */
    private void create3NewWaysAndSaveThem(Obstacle ob, WayBlacklist wayToBeRemoved, Way removedWayObject) {
        // Create 3 Ways
        List<Node> startPiece_nl = new ArrayList<Node>();
        List<Node> middlePiece_nl = new ArrayList<Node>();
        List<Node> endPiece_nl = new ArrayList<Node>();
        int phase = 1;
        for(Long l:wayToBeRemoved.getNodes()){
            if(phase == 1){
                if(l == ob.getId_firstnode()){
                    phase = 2;
                    // StartPiece ends with the beginning of Stair
                    startPiece_nl.add(new Node(ob.getLatitudeStart(), ob.getLongitudeStart()));
                }
                else{
                    startPiece_nl.add(new Node(getLatitudeFromNode(l), getLongitudeFromNode(l)));
                }
            }
            if(phase == 2){
                middlePiece_nl.add(new Node(ob.getLatitudeStart(),ob.getLongitudeStart()));
                middlePiece_nl.add(new Node(ob.getLatitudeEnd(),ob.getLongitudeEnd()));
                phase = 3;
            }
            if(phase == 3){
                endPiece_nl.add(new Node(getLatitudeFromNode(l), getLongitudeFromNode(l)));
            }
        }
        Way startPiece = new Way("", startPiece_nl);
        startPiece.setOsm_id(nextPossibleWayId);
        nextPossibleWayId++;
        startPiece.setAdditionalTags(wayToBeRemoved.getTags());

        // The Stair itself
        Way middlePiece = new Way("",middlePiece_nl);
        middlePiece.setOsm_id(nextPossibleWayId);
        nextPossibleWayId++;
        middlePiece.setAdditionalTags(getHStoreValue(ob));
        middlePiece.setIsObstacle(true);

        Way endPiece = new Way("", endPiece_nl);
        endPiece.setOsm_id(nextPossibleWayId);
        nextPossibleWayId++;
        endPiece.setAdditionalTags(wayToBeRemoved.getTags());

        // Save 2 Objects in OSM DB
        Obstacle second = cloneObstacle(ob);
        second.setLongitudeEnd(0);
        second.setLatitudeEnd(0);
        second.setLongitudeStart(ob.getLongitudeEnd());
        second.setLatitudeStart(ob.getLatitudeEnd());
        Obstacle first = ob;
        first.setLongitudeEnd(0);
        first.setLatitudeEnd(0);
        List<Obstacle> twoObstacles = new ArrayList<Obstacle>();
        twoObstacles.add(first);
        twoObstacles.add(second);
        writeOneNodeObstaclesInOsmDatabase(twoObstacles);

        // Save 3 Ways in OSM Database
        List<Way> threeways = new ArrayList<Way>();
        threeways.add(startPiece);
        threeways.add(middlePiece);
        threeways.add(endPiece);
        writeWaysInOsmDatabase(threeways);

        // Save 3 Ways in Hibernate
        for(Way w:threeways) postInTableWayHibernate(w);
        System.out.println("3 Ways have been posted to HibernateDB");
    }

    /**
     *
     * @param osm_id
     * @return give back latitude from Node given osm_id
     */
    private double getLatitudeFromNode(long osm_id){
        try {
            pstmt_selectLatitudeFromNode.setLong(1, osm_id);
            ResultSet rs = pstmt_selectLatitudeFromNode.executeQuery();
            if(rs.next()) return rs.getDouble("latitude");
            rs.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    /**
     *
     * @param osm_id
     * @return give back latitude from Node given osm_id
     */
    private double getLongitudeFromNode(long osm_id){
        try {
            pstmt_selectLongitudeFromNode.setLong(1, osm_id);
            ResultSet rs = pstmt_selectLongitudeFromNode.executeQuery();
            if(rs.next()) return rs.getDouble("longitude");
            rs.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    /**
     *
     * @param ob
     * @return give back a deep copy of the Obstacle ob
     */
    private Obstacle cloneObstacle(Obstacle ob) {
        if(ob instanceof Stairs){
            Stairs oldStair = (Stairs) ob;
            Stairs newStair = new Stairs(oldStair.getName(),oldStair.getLongitudeStart(),
                    oldStair.getLatitudeStart(), oldStair.getLongitudeEnd(), oldStair.getLatitudeEnd(),oldStair.getmNumberOfStairs(),oldStair.getHandrail());
            newStair.setIncline(oldStair.getIncline());
            newStair.setHandrail(oldStair.getHandrail());
            newStair.setTactile_paving(oldStair.getTactile_paving());
            newStair.setTactile_writing(oldStair.getTactile_writing());
            newStair.setRamp(oldStair.getRamp());
            newStair.setRamp_stroller(oldStair.getRamp_stroller());
            newStair.setRamp_wheelchair(oldStair.getRamp_wheelchair());
            newStair.setWidth(oldStair.getWidth());
            return newStair;
        }
        Stairs result = new Stairs(ob.getName(),ob.getLongitudeStart(),
                ob.getLatitudeStart(), ob.getLongitudeEnd(), ob.getLatitudeEnd(),0, null);
        return new Stairs();
    }

    /**
     * write all the ways in OSM Database
     */
    private void writeWaysInOSMDatabase(){
        List<Way> waysList = getAllWays();
        if(waysList.isEmpty()) return;
        System.out.println("Number of Ways: " + waysList.size());
        writeWaysInOsmDatabase(waysList);
        updateAlredyExportedWayAndNode(waysList);
    }

    private void writeWaysInOsmDatabase(List<Way> waysList) {
        try {
            PreparedStatement insertInTableWays = null;
            PreparedStatement insertInTableWay_nodes = null;
            PreparedStatement insertInTableNodes = null;

            String sql_insertInTableWays = "INSERT INTO ways (id, version, user_id, tstamp, changeset_id, tags, nodes) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?);";
            String sql_insertInTableWay_nodes = "INSERT INTO way_nodes (way_id, node_id, sequence_id) "
                    + "VALUES (?, ?, ?);";
            String sql_insertInTableNodes = "INSERT INTO nodes (id, version, user_id, tstamp, changeset_id, tags, geom) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326));";

            insertInTableWays = c.prepareStatement(sql_insertInTableWays);
            insertInTableWay_nodes = c.prepareStatement(sql_insertInTableWay_nodes);
            insertInTableNodes = c.prepareStatement(sql_insertInTableNodes);

            for(Way w:waysList){
                insertWayInTableWay(w, insertInTableWays);
                insertWayInTableWayNodes(w, insertInTableWay_nodes);
                insertNodeInTableNodes(w, insertInTableNodes);
            }
            insertInTableWays.close();
            insertInTableWay_nodes.close();
            insertInTableNodes.close();
            //c.commit();
            System.out.println("UPDATE OSM DB COMPLETED");
        } catch (SQLException e){
            e.printStackTrace();
            try {
                c.rollback();
            } catch (SQLException e1) {
                e1.printStackTrace();
            }
        }
    }

    private List<Way> getAllWays() {
        List<Way> datalist = ExportTool.getDataAsList(Way.class);

        //TODO remove println
        System.out.println("Number of Ways before check:"+datalist.size());
        Iterator<Way> iter = datalist.iterator();
        while(iter.hasNext()){
            Way w = iter.next();
            if(w.isAlreadyExported()) iter.remove();
        }
        System.out.println("Number of Ways after check:"+datalist.size());
        return datalist;
    }

    private void insertWayInTableWay(Way w, PreparedStatement insertInTableWays) {
        long id = w.getOsm_id();
        int version = -1;
        int userID = -1;
        long changesetID = -1;
        String tags = getHStoreValue(w);
        List<Node> nodesList = w.getNodes();
        Long[] nodeIDsArray = new Long[nodesList.size()];
        for(int i = 0; i < nodeIDsArray.length; i++){
            nodeIDsArray[i] = Long.valueOf(nodesList.get(i).getOsm_id());
        }
        try {
            Array nodes_result = c.createArrayOf("BIGINT", nodeIDsArray);
            insertInTableWays.setLong(1,id);
            insertInTableWays.setInt(2,version);
            insertInTableWays.setInt(3,userID);
            insertInTableWays.setTimestamp(4,current_timestamp);
            insertInTableWays.setLong(5, changesetID);
            insertInTableWays.setObject(6, tags,Types.OTHER);
            insertInTableWays.setArray(7,nodes_result);
            insertInTableWays.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void insertWayInTableWayNodes(Way w, PreparedStatement insertInTableWay_nodes) {
        long id = w.getOsm_id();
        int index = 0;
        for(Node n:w.getNodes()){
            try {
                insertInTableWay_nodes.setLong(1, id);
                insertInTableWay_nodes.setLong(2, n.getOsm_id());
                insertInTableWay_nodes.setInt(3, index);
                index++;
                insertInTableWay_nodes.execute();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    private void insertNodeInTableNodes(Way w, PreparedStatement insertInTableNodes) {
        int version = -1;
        int userID = -1;
        long changesetID = -1;

        for(Node n:w.getNodes()){
            if(nodeExistInOSMDB(n.getOsm_id())) continue;
            try {
                insertInTableNodes.setLong(1, n.getOsm_id());
                insertInTableNodes.setInt(2, version);
                insertInTableNodes.setInt(3, userID);
                insertInTableNodes.setTimestamp(4, current_timestamp);
                insertInTableNodes.setLong(5, changesetID);
                insertInTableNodes.setObject(6, getHStoreValue(n),Types.OTHER);
                insertInTableNodes.setDouble(7, n.getLongitude());
                insertInTableNodes.setDouble(8, n.getLatitude());
                insertInTableNodes.execute();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean nodeExistInOSMDB(long osm_id) {
        try {
            pstmt_checkIfOSMIDExistInNode.setLong(1,osm_id);
            ResultSet rs = pstmt_checkIfOSMIDExistInNode.executeQuery();
            if(!rs.isBeforeFirst()) return true;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     *
     * @param ob an Obstacle Object
     * @return a string in HStore format which contains all tag of the Obstacle o
     */
    private String getHStoreValue(Object ob){
        // TODO Docu about every single self defined Tags
        HashMap<String,String> tags = new HashMap<>();
        if(ob instanceof Obstacle){
            Obstacle o = (Obstacle)ob;
            if(o.getName() != null){
                if(!o.getName().equals("")) tags.put("name", o.getName());
            }
            switch (o.getTypeCode()){
                case STAIRS:
                    Stairs stair = (Stairs)o;
                    tags.put("highway","steps");
                    if(stair.getIncline() != null) tags.put("incline",stair.getIncline());
                    if(stair.getmNumberOfStairs() != 0) tags.put("step_count",String.valueOf(stair.getmNumberOfStairs()));
                    if(stair.getHandrail() != null) tags.put("handrail",stair.getHandrail());
                    if(stair.getTactile_paving() != null) tags.put("tactile_paving",stair.getTactile_paving());
                    if(stair.getTactile_writing() != null) tags.put("tactile_writing",stair.getTactile_writing());
                    if(stair.getRamp() != null) tags.put("ramp",stair.getRamp());
                    if(stair.getRamp_stroller() != null) tags.put("ramp:stroller",stair.getRamp_stroller());
                    if(stair.getRamp_wheelchair() != null) tags.put("ramp:wheelchair",stair.getRamp_wheelchair());
                    if(stair.getWidth() != 0) tags.put("width",String.valueOf(stair.getWidth()));
                    break;
                case UNEVENNESS:
                    Unevenness uneven = (Unevenness)o;
                    tags.put("barrier","unevenness");
                    tags.put("length", String.valueOf(uneven.getLength()));
                    break;
                case CONSTRUCTION:
                    Construction construction = (Construction)o;
                    tags.put("barrier","construction");
                    tags.put("size", String.valueOf(construction.getSize()));
                    DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
                    if (construction.getValidUntil() != null){
                        tags.put("expire", String.valueOf(formatter.format(construction.getValidUntil())));
                    }
                    // TODO Change code in Construction.class, either standard ValidUntil
                    // or set the value while creating obstacle object FRONT END Job
                    else tags.put("expire", "2017-08-28");
                    break;
                case FAST_TRAFFIC_LIGHT:
                    FastTrafficLight tflight = (FastTrafficLight)o;
                    tags.put("barrier","fasttrafficlight");
                    tags.put("duration",String.valueOf(tflight.getDuration()));
                    break;
                case ELEVATOR:
                    // TODO maybe remove from to attribute from BP.Common
                    Elevator ele = (Elevator)o;
                    tags.put("barrier","elevator");
                    break;
                case TIGHT_PASSAGE:
                    TightPassage tpass = (TightPassage)o;
                    tags.put("barrier","tightpassage");
                    tags.put("passagewidth", String.valueOf(((TightPassage) o).getWidth()));
                    break;
                default:
                    break;
            }
        }
        else if(ob instanceof Way){
            Way w = (Way)ob;
            if(!w.getAdditionalTags().equals("")){
                //System.out.println("Way:"+w+" return "+w.getAdditionalTags());
                return w.getAdditionalTags();
            }
            else if(!w.getName().equals("")) tags.put("name",w.getName());
            tags.put("highway",w.getHighway());
        }
        //System.out.println("Object "+ob+" returns "+HStoreConverter.toString(tags));
        return HStoreConverter.toString(tags);
    }

    private void updateAlredyExportedWayAndNode(List<Way> waysList) {
        Session session = null;
        Transaction tx = null;

        try {
            session =  DatabaseSessionManager.instance().getSessionFactory().openSession();
            tx = session.beginTransaction();
            for(Way w:waysList){
                w.setAlreadyExported(true);
                session.saveOrUpdate(w);
                for(Node n:w.getNodes()){
                    n.setAlreadyExported(true);
                    session.saveOrUpdate(n);
                }
            }
            tx.commit();

        }catch (Exception ex) {
            ex.printStackTrace();
            tx.rollback();
        }
        finally {session.close();}
        System.out.println("All ways marked as exported in HibernateDB.");
    }

    private void updateAlreadyExportedObstacle(List<Obstacle> obstacleList){
        Session session = null;
        Transaction tx = null;

        try {
            session =  DatabaseSessionManager.instance().getSessionFactory().openSession();
            tx = session.beginTransaction();
            for(Obstacle o:obstacleList){
                o.setAlreadyExported(true);
                session.saveOrUpdate(o);
            }
            tx.commit();

        }catch (Exception ex) {
            ex.printStackTrace();
            tx.rollback();
        }
        finally {session.close();}
        System.out.println("All Obstacles marked as exported in HibernateDB.");
    }

    /**
     * close up all opened connections. This is called at last.
     */
    private void closeUpAllConnections() {
        try {
            pstmt_checkIfOSMIDExistInNode.close();
            pstmt_selectLongitudeFromNode.close();
            pstmt_selectLatitudeFromNode.close();
            c.close();
            hibernate_con.close();
            DatabaseSessionManager.instance().getSessionFactory().close();
            System.out.println("All Connections closing up.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        ExportTool.getInstance().startExportProcess();
        Connection c = PostgreSQLJDBC.getInstance().getConnection();
        System.out.println("ExportTool.jar SUCCESSFUL EXECUTED");
    }
}
