package org.observer;

import org.junit.Test;
import org.observer.utils.HierarchyUtil;

import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TestMain {
    @Test
    public void methodOwnerTest() {
        String call = "com.sun.rowset.JdbcRowSetImpl#setUrl#null#1";
        String[] callItems = call.split("#");
        String owner = HierarchyUtil.getMatchClzName(callItems[0], callItems[1], callItems[2], new HashMap<>());
        assertNotNull(owner);
        assertEquals("javax.sql.RowSet", owner);
    }

}
