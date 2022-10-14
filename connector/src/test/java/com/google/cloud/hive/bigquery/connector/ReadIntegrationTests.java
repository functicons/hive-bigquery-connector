package com.google.cloud.hive.bigquery.connector;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.cloud.hive.bigquery.connector.TestUtils.*;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import com.google.cloud.bigquery.TableResult;
import com.google.cloud.hive.bigquery.connector.config.HiveBigQueryConfig;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.cartesian.CartesianTest;

public class ReadIntegrationTests extends IntegrationTestsBase {

    /** Check that attempting to read a table that doesn't exist fails gracefully with a useful error message */
    @CartesianTest
    public void testReadNonExistingTable(
        @CartesianTest.Values(strings = {"mr", "tez"}) String engine,
        @CartesianTest.Values(strings = {HiveBigQueryConfig.ARROW, HiveBigQueryConfig.AVRO})
            String readDataFormat) {
        // Make sure the table doesn't exist in BigQuery
        dropBqTableIfExists(dataset, TEST_TABLE_NAME);
        assertFalse(bQTableExists(dataset, TEST_TABLE_NAME));
        // Create a Hive table without creating its corresponding table in BigQuery
        initHive(engine, readDataFormat);
        runHiveScript(HIVE_TEST_TABLE_CREATE_QUERY);
        // Attempt to read the table
        Throwable exception =
            assertThrows(
                RuntimeException.class,
                () -> runHiveStatement(String.format("SELECT * FROM %s", TEST_TABLE_NAME)));
        assertTrue(exception.getMessage().contains(
            String.format("Table '%s.%s.%s' not found", getProject(), dataset, TEST_TABLE_NAME)));
    }

    // -----------------------------------------------------------------------------------------------

    /** Check that reading an empty BQ table actually returns 0 results. */
    @CartesianTest
    public void testReadEmptyTable(
        @CartesianTest.Values(strings = {"mr", "tez"}) String engine,
        @CartesianTest.Values(strings = {HiveBigQueryConfig.ARROW, HiveBigQueryConfig.AVRO})
            String readDataFormat) {
        runBqQuery(BIGQUERY_TEST_TABLE_CREATE_QUERY);
        initHive(engine, readDataFormat);
        runHiveScript(HIVE_TEST_TABLE_CREATE_QUERY);
        List<Object[]> rows = runHiveStatement(String.format("SELECT * FROM %s", TEST_TABLE_NAME));
        assertThat(rows).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------

    /** Test the WHERE clause */
    @CartesianTest
    public void testWhereClause(
        @CartesianTest.Values(strings = {"mr", "tez"}) String engine,
        @CartesianTest.Values(strings = {HiveBigQueryConfig.ARROW, HiveBigQueryConfig.AVRO})
            String readDataFormat) {
        runBqQuery(BIGQUERY_TEST_TABLE_CREATE_QUERY);
        initHive(engine, readDataFormat);
        runHiveScript(HIVE_TEST_TABLE_CREATE_QUERY);
        // Insert data into BQ using the BQ SDK
        runBqQuery(
            String.format(
                "INSERT `${dataset}.%s` VALUES (123, 'hello'), (999, 'abcd')", TEST_TABLE_NAME));
        // Make sure the initial data is there
        TableResult result =
            runBqQuery(String.format("SELECT * FROM `${dataset}.%s`", TEST_TABLE_NAME));
        assertEquals(2, result.getTotalRows());
        // Read filtered data using Hive
        List<Object[]> rows =
            runHiveStatement(
                String.format("SELECT * FROM %s WHERE number = 999", TEST_TABLE_NAME));
        // Verify we get the expected rows
        assertArrayEquals(
            new Object[] {
                new Object[] {999L, "abcd"},
            },
            rows.toArray());
        // TODO: Confirm that the predicate was in fact pushed down to BigQuery
    }

    // ---------------------------------------------------------------------------------------------------

    /** Test the `SELECT` statement with explicit columns (i.e. not `SELECT *`) */
    @CartesianTest
    public void testSelectExplicitColumns(
        @CartesianTest.Values(strings = {"mr", "tez"}) String engine,
        @CartesianTest.Values(strings = {HiveBigQueryConfig.ARROW, HiveBigQueryConfig.AVRO})
            String readDataFormat) {
        runBqQuery(BIGQUERY_TEST_TABLE_CREATE_QUERY);
        initHive(engine, readDataFormat);
        runHiveScript(HIVE_TEST_TABLE_CREATE_QUERY);
        // Insert data into BQ using the BQ SDK
        runBqQuery(
            String.format(
                "INSERT `${dataset}.%s` VALUES (123, 'hello'), (999, 'abcd')", TEST_TABLE_NAME));
        TableResult result =
            runBqQuery(String.format("SELECT * FROM `${dataset}.%s`", TEST_TABLE_NAME));
        // Make sure the initial data is there
        assertEquals(2, result.getTotalRows());
        // Read filtered data using Hive
        // Try with both columns in order
        List<Object[]> rows =
            runHiveStatement(
                String.format("SELECT number, text FROM %s ORDER BY number", TEST_TABLE_NAME));
        assertArrayEquals(
            new Object[] {
                new Object[] {123L, "hello"},
                new Object[] {999L, "abcd"}
            },
            rows.toArray());
        // Try in different order
        rows =
            runHiveStatement(
                String.format("SELECT text, number FROM %s ORDER BY number", TEST_TABLE_NAME));
        assertArrayEquals(
            new Object[] {
                new Object[] {"hello", 123L},
                new Object[] {"abcd", 999L}
            },
            rows.toArray());
        // Try a single column
        rows =
            runHiveStatement(
                String.format("SELECT number FROM %s ORDER BY number", TEST_TABLE_NAME));
        assertArrayEquals(new Object[] {new Object[] {123L}, new Object[] {999L}}, rows.toArray());
        // Try another single column
        rows =
            runHiveStatement(String.format("SELECT text FROM %s ORDER BY text", TEST_TABLE_NAME));
        assertArrayEquals(new Object[] {new Object[] {"abcd"}, new Object[] {"hello"}}, rows.toArray());
    }

    // ---------------------------------------------------------------------------------------------------

    /** Smoke test to make sure BigQuery accepts all different types of pushed predicates */
    @Test
    public void testWhereClauseAllTypes() {
        runBqQuery(BIGQUERY_ALL_TYPES_TABLE_CREATE_QUERY);
        initHive();
        runHiveScript(HIVE_ALL_TYPES_TABLE_CREATE_QUERY);
        runHiveScript(
            Stream.of(
                    "SELECT * FROM " + ALL_TYPES_TABLE_NAME + " WHERE",
                    "((int_val > 10 AND bl = TRUE)",
                    "OR (str = 'hello' OR day >= to_date('2000-01-01')))",
                    "AND (ts BETWEEN TIMESTAMP'2018-09-05 00:10:04.19' AND"
                        + " TIMESTAMP'2019-06-11 03:55:10.00')",
                    "AND (fl <= 4.2)")
                .collect(Collectors.joining("\n")));
        // TODO: Confirm that the predicates were in fact pushed down to BigQuery
    }

    // ---------------------------------------------------------------------------------------------------

    /** Test the "SELECT COUNT(*)" statement. */
    @CartesianTest
    public void testCount(
        @CartesianTest.Values(strings = {"mr", "tez"}) String engine,
        @CartesianTest.Values(strings = {HiveBigQueryConfig.ARROW, HiveBigQueryConfig.AVRO})
            String readDataFormat) {
        // Create some initial data in BQ
        runBqQuery(BIGQUERY_TEST_TABLE_CREATE_QUERY);
        runBqQuery(
            String.format(
                "INSERT `${dataset}.%s` VALUES (123, 'hello'), (999, 'abcd')", TEST_TABLE_NAME));
        TableResult result =
            runBqQuery(String.format("SELECT * FROM `${dataset}.%s`", TEST_TABLE_NAME));
        // Make sure the initial data is there
        assertEquals(2, result.getTotalRows());
        // Run COUNT query in Hive
        initHive(engine, readDataFormat);
        runHiveScript(HIVE_TEST_TABLE_CREATE_QUERY);
        List<Object[]> rows = runHiveStatement("SELECT COUNT(*) FROM " + TEST_TABLE_NAME);
        assertEquals(1, rows.size());
        assertEquals(2L, rows.get(0)[0]);
    }

    // ---------------------------------------------------------------------------------------------------

    /** Check that we can read all types of data from BigQuery. */
    @CartesianTest
    public void testReadAllTypes(
        @CartesianTest.Values(strings = {HiveBigQueryConfig.ARROW, HiveBigQueryConfig.AVRO})
            String readDataFormat) {
        // Create the BQ table
        runBqQuery(BIGQUERY_ALL_TYPES_TABLE_CREATE_QUERY);
        // Insert data into the BQ table using the BQ SDK
        runBqQuery(
            Stream.of(
                    String.format("INSERT `${dataset}.%s` VALUES (", ALL_TYPES_TABLE_NAME),
                    "42,",
                    "true,",
                    "\"string\",",
                    "cast(\"2019-03-18\" as date),",
                    "cast(\"2019-03-18T01:23:45.678901\" as timestamp),",
                    "cast(\"bytes\" as bytes),",
                    "4.2,",
                    "struct(",
                    "  cast(\"-99999999999999999999999999999.999999999\" as numeric),",
                    "  cast(\"99999999999999999999999999999.999999999\" as numeric),",
                    "  cast(3.14 as numeric),",
                    "  cast(\"31415926535897932384626433832.795028841\" as numeric)",
                    "),",
                    "[1, 2, 3],",
                    "[(select as struct 1)]",
                    ")")
                .collect(Collectors.joining("\n")));
        // Read the data using Hive
        initHive("mr", readDataFormat);
        runHiveScript(HIVE_ALL_TYPES_TABLE_CREATE_QUERY);
        List<Object[]> rows = runHiveStatement("SELECT * FROM " + ALL_TYPES_TABLE_NAME);
        assertEquals(1, rows.size());
        Object[] row = rows.get(0);
        assertEquals(10, row.length); // Number of columns
        assertEquals(42L, (long) row[0]);
        assertEquals(true, row[1]);
        assertEquals("string", row[2]);
        assertEquals("2019-03-18", row[3]);
        assertEquals("2019-03-18 01:23:45.678901", row[4]);
        assertArrayEquals("bytes".getBytes(), (byte[]) row[5]);
        assertEquals(4.2, row[6]);
        assertEquals(
            "{\"min\":-99999999999999999999999999999.999999999,\"max\":99999999999999999999999999999.999999999,\"pi\":3.14,\"big_pi\":31415926535897932384626433832.795028841}",
            row[7]);
        assertEquals("[1,2,3]", row[8]);
        assertEquals("[{\"i\":1}]", row[9]);
    }

    // ---------------------------------------------------------------------------------------------------

    /** Join two tables */
    @CartesianTest
    public void testInnerJoin(
        @CartesianTest.Values(strings = {"mr", "tez"}) String engine,
        @CartesianTest.Values(strings = {HiveBigQueryConfig.ARROW, HiveBigQueryConfig.AVRO})
            String readDataFormat) {
        // Create the BQ tables
        runBqQuery(BIGQUERY_TEST_TABLE_CREATE_QUERY);
        runBqQuery(BIGQUERY_ANOTHER_TEST_TABLE_CREATE_QUERY);
        // Insert data into the BQ tables using the BQ SDK
        runBqQuery(
            Stream.of(
                    String.format("INSERT `${dataset}.%s` VALUES", TEST_TABLE_NAME),
                    "(1, 'hello'), (2, 'bonjour'), (1, 'hola')")
                .collect(Collectors.joining("\n")));
        runBqQuery(
            Stream.of(
                    String.format("INSERT `${dataset}.%s` VALUES", ANOTHER_TEST_TABLE_NAME),
                    "(1, 'red'), (2, 'blue'), (3, 'green')")
                .collect(Collectors.joining("\n")));
        // Create the Hive tables
        initHive(engine, readDataFormat);
        runHiveScript(HIVE_TEST_TABLE_CREATE_QUERY);
        runHiveScript(HIVE_ANOTHER_TEST_TABLE_CREATE_QUERY);
        // Do an inner join of the two tables using Hive
        List<Object[]> rows =
            runHiveStatement(
                Stream.of(
                        "SELECT",
                        "t2.number,",
                        "t1.str_val,",
                        "t2.text",
                        "FROM " + ANOTHER_TEST_TABLE_NAME + " t1",
                        "JOIN " + TEST_TABLE_NAME + " t2",
                        "ON (t1.num = t2.number)",
                        "ORDER BY t2.number, t1.str_val, t2.text")
                    .collect(Collectors.joining("\n")));
        assertArrayEquals(
            new Object[] {
                new Object[] {1L, "red", "hello"},
                new Object[] {1L, "red", "hola"},
                new Object[] {2L, "blue", "bonjour"},
            },
            rows.toArray());
    }

    // ---------------------------------------------------------------------------------------------------

    /** Read from multiple tables in the same query. */
    @CartesianTest
    public void testMultiRead(
        @CartesianTest.Values(strings = {"mr", "tez"}) String engine,
        @CartesianTest.Values(strings = {HiveBigQueryConfig.ARROW, HiveBigQueryConfig.AVRO})
            String readDataFormat) {
        // Create the BQ tables
        runBqQuery(BIGQUERY_TEST_TABLE_CREATE_QUERY);
        runBqQuery(BIGQUERY_ANOTHER_TEST_TABLE_CREATE_QUERY);
        // Insert data into the BQ tables using the BQ SDK
        runBqQuery(
            Stream.of(
                    String.format("INSERT `${dataset}.%s` VALUES", TEST_TABLE_NAME),
                    "(1, 'hello1'), (2, 'hello2'), (3, 'hello3')")
                .collect(Collectors.joining("\n")));
        runBqQuery(
            Stream.of(
                    String.format("INSERT `${dataset}.%s` VALUES", ANOTHER_TEST_TABLE_NAME),
                    "(123, 'hi123'), (42, 'hi42'), (999, 'hi999')")
                .collect(Collectors.joining("\n")));
        // Create the Hive tables
        initHive(engine, readDataFormat);
        runHiveScript(HIVE_TEST_TABLE_CREATE_QUERY);
        runHiveScript(HIVE_ANOTHER_TEST_TABLE_CREATE_QUERY);
        // Read from multiple table in same Hive query
        List<Object[]> rows =
            runHiveStatement(
                Stream.of(
                        "SELECT",
                        "*",
                        "FROM (",
                        "SELECT",
                        "t1.num as number,",
                        "t1.str_val as text",
                        "FROM " + ANOTHER_TEST_TABLE_NAME + " t1",
                        "UNION ALL",
                        "SELECT *",
                        "FROM " + TEST_TABLE_NAME + " t2",
                        ") unioned_table ORDER BY number")
                    .collect(Collectors.joining("\n")));
        assertArrayEquals(
            new Object[] {
                new Object[] {1L, "hello1"},
                new Object[] {2L, "hello2"},
                new Object[] {3L, "hello3"},
                new Object[] {42L, "hi42"},
                new Object[] {123L, "hi123"},
                new Object[] {999L, "hi999"},
            },
            rows.toArray());
    }

}
