package org.iplantc.workflow.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.iplantc.workflow.integration.util.ImportUtils;
import org.json.JSONException;
import org.json.JSONObject;

import org.junit.Test;

/**
 * Unit tests for org.iplantc.workflow.create.ImportUtils
 * 
 * @author Dennis Roberts
 */
public class ImportUtilsTest {

	/**
	 * Verifies that we can generate an identifier with a prefix.
	 */
	@Test
	public void testIdWithPrefix() {
		String id = ImportUtils.generateId("foo");
		assertTrue(id.matches("foo[0-9a-f]{32}"));
	}

	/**
	 * Verifies that we can generate an identifier without a prefix.
	 */
	@Test
	public void testIdWithoutPrefix() {
		String id = ImportUtils.generateId("");
		assertTrue(id.matches("[0-9a-f]{32}"));
	}
	
	/**
	 * Verifies that we can obtain a specified identifier from a JSON object.
	 * 
	 * @throws JSONException if an invalid field name is used.
	 */
	@Test
	public void testGetIdWithSpecifiedId() throws JSONException {
	    JSONObject json = new JSONObject();
	    json.put("id", "someid");
	    assertEquals("someid", ImportUtils.getId(json, "id", "i"));
	}

	/**
	 * Verifies that an identifier will be generated if one isn't specified.
	 * 
	 * @throws JSONException if an invalid field name is used.
	 */
	@Test
	public void testGetIdWithUnspecifiedId() throws JSONException {
	    JSONObject json = new JSONObject();
	    String id = ImportUtils.getId(json, "id", "i");
	    assertTrue(id.matches("i[0-9a-f]{32}"));
	}
}
