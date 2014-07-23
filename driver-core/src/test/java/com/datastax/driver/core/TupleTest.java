package com.datastax.driver.core;

import java.util.*;

import com.google.common.base.Joiner;
import org.testng.annotations.Test;

import static com.datastax.driver.core.DataTypeIntegrationTest.getSampleData;
import static com.datastax.driver.core.TestUtils.versionCheck;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class TupleTest extends CCMBridge.PerClassSingleNodeCluster {

    @Override
    protected Collection<String> getTableDefinitions() {
        versionCheck(2.1, 0, "This will only work with Cassandra 2.1.0");

        return Arrays.asList("CREATE TABLE t (k int PRIMARY KEY, v tuple<int, text, float>)");
    }

    @Test(groups = "short")
    public void simpleValueTest() throws Exception {
        TupleType t = TupleType.of(DataType.cint(), DataType.text(), DataType.cfloat());
        TupleValue v = t.newValue();
        v.setInt(0, 1);
        v.setString(1, "a");
        v.setFloat(2, 1.0f);

        assertEquals(v.getType().getComponentTypes().size(), 3);
        assertEquals(v.getType().getComponentTypes().get(0), DataType.cint());
        assertEquals(v.getType().getComponentTypes().get(1), DataType.text());
        assertEquals(v.getType().getComponentTypes().get(2), DataType.cfloat());

        assertEquals(v.getInt(0), 1);
        assertEquals(v.getString(1), "a");
        assertEquals(v.getFloat(2), 1.0f);

        assertEquals(t.format(v), "(1, 'a', 1.0)");
    }

    @Test(groups = "short")
    public void simpleWriteReadTest() throws Exception {
        try {
            PreparedStatement ins = session.prepare("INSERT INTO t(k, v) VALUES (?, ?)");
            PreparedStatement sel = session.prepare("SELECT * FROM t WHERE k=?");

            TupleType t = TupleType.of(DataType.cint(), DataType.text(), DataType.cfloat());

            int k = 1;
            TupleValue v = t.newValue(1, "a", 1.0f);

            session.execute(ins.bind(k, v));
            TupleValue v2 = session.execute(sel.bind(k)).one().getTupleValue("v");

            assertEquals(v2, v);

            // Test simple statement interpolation
            k = 2;
            v = t.newValue(2, "b", 2.0f);

            session.execute("INSERT INTO t(k, v) VALUES (?, ?)", k, v);
            v2 = session.execute(sel.bind(k)).one().getTupleValue("v");

            assertEquals(v2, v);
        } catch (Exception e) {
            errorOut();
            throw e;
        }
    }

    /**
     * Basic test of tuple functionality.
     * Original code found in python-driver:integration.standard.test_types.py:test_tuple_type
     * @throws Exception
     */
    @Test(groups = "short")
    public void tupleTypeTest() throws Exception {
        try {
            session.execute("CREATE KEYSPACE test_tuple_type " +
                            "WITH replication = { 'class' : 'SimpleStrategy', 'replication_factor': '1'}");
            session.execute("USE test_tuple_type");
            session.execute("CREATE TABLE mytable (a int PRIMARY KEY, b tuple<ascii, int, boolean>)");

            TupleType t = TupleType.of(DataType.ascii(), DataType.cint(), DataType.cboolean());

            // test non-prepared statement
            TupleValue complete = t.newValue("foo", 123, true);
            session.execute("INSERT INTO mytable (a, b) VALUES (0, ?)", complete);
            TupleValue r = session.execute("SELECT b FROM mytable WHERE a=0").one().getTupleValue("b");
            assertEquals(r, complete);

            // test incomplete tuples
            try {
                TupleValue partial = t.newValue("bar", 456);
                fail();
            } catch (IllegalArgumentException e) {}

            // test incomplete tuples with new TupleType
            TupleType t1 = TupleType.of(DataType.ascii(), DataType.cint());
            TupleValue partial = t1.newValue("bar", 456);
            TupleValue partionResult = t.newValue("bar", 456, null);
            session.execute("INSERT INTO mytable (a, b) VALUES (0, ?)", partial);
            r = session.execute("SELECT b FROM mytable WHERE a=0").one().getTupleValue("b");
            assertEquals(r, partionResult);

            // test single value tuples
            try {
                TupleValue subpartial = t.newValue("zoo");
                fail();
            } catch (IllegalArgumentException e) {}

            // test single value tuples with new TupleType
            TupleType t2 = TupleType.of(DataType.ascii());
            TupleValue subpartial = t2.newValue("zoo");
            TupleValue subpartialResult = t.newValue("zoo", null, null);
            session.execute("INSERT INTO mytable (a, b) VALUES (0, ?)", subpartial);
            r = session.execute("SELECT b FROM mytable WHERE a=0").one().getTupleValue("b");
            assertEquals(r, subpartialResult);

            // test prepared statements
            PreparedStatement prepared = session.prepare("INSERT INTO mytable (a, b) VALUES (?, ?)");
            session.execute(prepared.bind(3, complete));
            session.execute(prepared.bind(4, partial));
            session.execute(prepared.bind(5, subpartial));

            prepared = session.prepare("SELECT b FROM mytable WHERE a=?");
            assertEquals(session.execute(prepared.bind(3)).one().getTupleValue("b"), complete);
            assertEquals(session.execute(prepared.bind(4)).one().getTupleValue("b"), partionResult);
            assertEquals(session.execute(prepared.bind(5)).one().getTupleValue("b"), subpartialResult);

        } catch (Exception e) {
            errorOut();
            throw e;
        }
    }

    /**
     * Test tuple types of lengths of 1, 2, 3, and 384 to ensure edge cases work
     * as expected.
     * Original code found in python-driver:integration.standard.test_types.py:test_tuple_type_varying_lengths
     *
     * @throws Exception
     */
    @Test(groups = "short")
    public void tupleTestTypeVaryingLengths() throws Exception {
        try {
            session.execute("CREATE KEYSPACE test_tuple_type_varying_lengths " +
                            "WITH replication = { 'class' : 'SimpleStrategy', 'replication_factor': '1'}");
            session.execute("USE test_tuple_type_varying_lengths");

            // programmatically create the table with tuples of said sizes
            int[] lengths = {1, 2, 3, 384};
            ArrayList<String> valueSchema = new ArrayList<String>();
            for (int i : lengths) {
                ArrayList<String> ints = new ArrayList<String>();
                for (int j = 0; j < i; ++j) {
                    ints.add("int");
                }
                valueSchema.add(String.format(" v_%d tuple<%s>", i, Joiner.on(',').join(ints)));
            }
            session.execute(String.format("CREATE TABLE mytable (k int PRIMARY KEY, %s)", Joiner.on(',').join(valueSchema)));

            // insert tuples into same key using different columns
            // and verify the results
            for (int i : lengths) {
                // create tuple
                ArrayList<DataType> dataTypes = new ArrayList<DataType>();
                ArrayList<Integer> values = new ArrayList<Integer>();
                for (int j = 0; j < i; ++j) {
                    dataTypes.add(DataType.cint());
                    values.add(j);
                }
                TupleType t = new TupleType(dataTypes);
                TupleValue createdTuple = t.newValue(values.toArray());

                // write tuple
                session.execute(String.format("INSERT INTO mytable (k, v_%s) VALUES (0, ?)", i), createdTuple);

                // read tuple
                TupleValue r = session.execute(String.format("SELECT v_%s FROM mytable WHERE k=0", i)).one().getTupleValue(String.format("v_%s", i));
                assertEquals(r, createdTuple);
            }

        } catch (Exception e) {
            errorOut();
            throw e;
        }
    }

    /**
     * Ensure tuple subtypes are appropriately handled.
     * Original code found in python-driver:integration.standard.test_types.py:test_tuple_subtypes
     *
     * @throws Exception
     */
    @Test(groups = "short")
    public void tupleSubtypesTest() throws Exception {

        // hold onto constants
        ArrayList<DataType> DATA_TYPE_PRIMITIVES = new ArrayList<DataType>();
        for (DataType dt : DataType.allPrimitiveTypes()) {
            // skip counter types since counters are not allowed inside tuples
            if (dt == DataType.counter())
                continue;

            DATA_TYPE_PRIMITIVES.add(dt);
        }
        HashMap<DataType, Object> SAMPLE_DATA = getSampleData();

        try {
            session.execute("CREATE KEYSPACE test_tuple_subtypes " +
                            "WITH replication = { 'class' : 'SimpleStrategy', 'replication_factor': '1'}");
            session.execute("USE test_tuple_subtypes");

            // programmatically create the table with a tuple of all datatypes
            session.execute(String.format("CREATE TABLE mytable (k int PRIMARY KEY, v tuple<%s>)", Joiner.on(',').join(DATA_TYPE_PRIMITIVES)));

            // insert tuples into same key using different columns
            // and verify the results
            int i = 1;
            for (DataType datatype : DATA_TYPE_PRIMITIVES) {
                // create tuples to be written and ensure they match with the expected response
                // responses have trailing None values for every element that has not been written
                ArrayList<DataType> dataTypes = new ArrayList<DataType>();
                ArrayList<DataType> completeDataTypes = new ArrayList<DataType>();
                ArrayList<Object> createdValues = new ArrayList<Object>();
                ArrayList<Object> completeValues = new ArrayList<Object>();

                // create written portion of the arrays
                for (int j = 0; j < i; ++j) {
                    dataTypes.add(DATA_TYPE_PRIMITIVES.get(j));
                    completeDataTypes.add(DATA_TYPE_PRIMITIVES.get(j));
                    createdValues.add(SAMPLE_DATA.get(DATA_TYPE_PRIMITIVES.get(j)));
                    completeValues.add(SAMPLE_DATA.get(DATA_TYPE_PRIMITIVES.get(j)));
                }

                // complete portion of the arrays needed for trailing nulls
                for (int j = 0; j < DATA_TYPE_PRIMITIVES.size() - i; ++j) {
                    completeDataTypes.add(DATA_TYPE_PRIMITIVES.get(i + j));
                    completeValues.add(null);
                }

                // actually create the tuples
                TupleType t = new TupleType(dataTypes);
                TupleType t2 = new TupleType(completeDataTypes);
                TupleValue createdTuple = t.newValue(createdValues.toArray());
                TupleValue completeTuple = t2.newValue(completeValues.toArray());

                // write tuple
                session.execute(String.format("INSERT INTO mytable (k, v) VALUES (%s, ?)", i), createdTuple);

                // read tuple
                TupleValue r = session.execute("SELECT v FROM mytable WHERE k=?", i).one().getTupleValue("v");
                assertEquals(r.toString(), completeTuple.toString());
                ++i;
            }

        } catch (Exception e) {
            errorOut();
            throw e;
        }
    }
}
