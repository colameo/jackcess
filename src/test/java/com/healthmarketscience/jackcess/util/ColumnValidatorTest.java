/*
Copyright (c) 2014 James Ahlborn

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation; either
version 2.1 of the License, or (at your option) any later version.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public
License along with this library; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307
USA
*/

package com.healthmarketscience.jackcess.util;

import java.util.List;
import java.util.Map;

import com.healthmarketscience.jackcess.Column;
import com.healthmarketscience.jackcess.ColumnBuilder;
import com.healthmarketscience.jackcess.CursorBuilder;
import com.healthmarketscience.jackcess.DataType;
import com.healthmarketscience.jackcess.Database;
import static com.healthmarketscience.jackcess.Database.*;
import static com.healthmarketscience.jackcess.DatabaseTest.*;
import com.healthmarketscience.jackcess.IndexCursor;
import com.healthmarketscience.jackcess.Row;
import com.healthmarketscience.jackcess.Table;
import com.healthmarketscience.jackcess.TableBuilder;
import static com.healthmarketscience.jackcess.impl.JetFormatTest.*;
import junit.framework.TestCase;

/**
 *
 * @author James Ahlborn
 */
public class ColumnValidatorTest extends TestCase 
{

  public ColumnValidatorTest(String name) {
    super(name);
  }

  public void testValidate() throws Exception {
    for (final FileFormat fileFormat : SUPPORTED_FILEFORMATS) {
      Database db = create(fileFormat);

      ColumnValidatorFactory initFact = db.getColumnValidatorFactory();
      assertNotNull(initFact);

      Table table = new TableBuilder("Test")
        .addColumn(new ColumnBuilder("id", DataType.LONG).setAutoNumber(true))
        .addColumn(new ColumnBuilder("data", DataType.TEXT))
        .addColumn(new ColumnBuilder("num", DataType.LONG))
        .setPrimaryKey("id")
        .toTable(db);

      for(Column col : table.getColumns()) {
        assertSame(SimpleColumnValidator.INSTANCE, col.getColumnValidator());
      }

      int val = -1;
      for(int i = 1; i <= 3; ++i) {
        table.addRow(Column.AUTO_NUMBER, "row" + i, val++);
      }

      table = null;

      // force table to be reloaded
      clearTableCache(db);
      
      final ColumnValidator cv = new ColumnValidator() {
        public Object validate(Column col, Object v1) {
          Number num = (Number)v1;
          if((num == null) || (num.intValue() < 0)) {
            throw new IllegalArgumentException("not gonna happen");
          }
          return v1;
        }
      };
            
      ColumnValidatorFactory fact = new ColumnValidatorFactory() {
        public ColumnValidator createValidator(Column col) {
          Table t = col.getTable();
          assertFalse(t.isSystem());
          if(!"Test".equals(t.getName())) {
            return null;
          }

          if(col.getType() == DataType.LONG) {
            return cv;
          }

          return null;
        }
      };

      db.setColumnValidatorFactory(fact);

      table = db.getTable("Test");
      
      for(Column col : table.getColumns()) {
        ColumnValidator cur = col.getColumnValidator();
        assertNotNull(cur);
        if("num".equals(col.getName())) {
          assertSame(cv, cur);
        } else {
          assertSame(SimpleColumnValidator.INSTANCE, cur);
        }
      }
      
      Column idCol = table.getColumn("id");
      Column dataCol = table.getColumn("data");
      Column numCol = table.getColumn("num");

      try {
        idCol.setColumnValidator(cv);
        fail("IllegalArgumentException should have been thrown");
      } catch(IllegalArgumentException e) {
        // success
      }
      assertSame(SimpleColumnValidator.INSTANCE, idCol.getColumnValidator());
      
      try {
        table.addRow(Column.AUTO_NUMBER, "row4", -3);
        fail("IllegalArgumentException should have been thrown");
      } catch(IllegalArgumentException e) {
        assertEquals("not gonna happen", e.getMessage());
      }

      table.addRow(Column.AUTO_NUMBER, "row4", 4);

      List<? extends Map<String, Object>> expectedRows =
        createExpectedTable(
            createExpectedRow("id", 1, "data", "row1", "num", -1),
            createExpectedRow("id", 2, "data", "row2", "num", 0),
            createExpectedRow("id", 3, "data", "row3", "num", 1),
            createExpectedRow("id", 4, "data", "row4", "num", 4));
      
      assertTable(expectedRows, table);

      IndexCursor pkCursor = CursorBuilder.createPrimaryKeyCursor(table);
      assertNotNull(pkCursor.findRowByEntry(1));
      
      pkCursor.setCurrentRowValue(dataCol, "row1_mod");

      assertEquals(createExpectedRow("id", 1, "data", "row1_mod", "num", -1),
                   pkCursor.getCurrentRow());

      try {
        pkCursor.setCurrentRowValue(numCol, -2);
        fail("IllegalArgumentException should have been thrown");
      } catch(IllegalArgumentException e) {
        assertEquals("not gonna happen", e.getMessage());
      }

      assertEquals(createExpectedRow("id", 1, "data", "row1_mod", "num", -1),
                   pkCursor.getCurrentRow());

      Row row3 = CursorBuilder.findRowByPrimaryKey(table, 3);

      row3.put("num", -2);

      try {
        table.updateRow(row3);
        fail("IllegalArgumentException should have been thrown");
      } catch(IllegalArgumentException e) {
        assertEquals("not gonna happen", e.getMessage());
      }

      assertEquals(createExpectedRow("id", 3, "data", "row3", "num", 1),
                   CursorBuilder.findRowByPrimaryKey(table, 3));

      final ColumnValidator cv2 = new ColumnValidator() {
        public Object validate(Column col, Object v1) {
          Number num = (Number)v1;
          if((num == null) || (num.intValue() < 0)) {
            return 0;
          }
          return v1;
        }
      };

      numCol.setColumnValidator(cv2);

      table.addRow(Column.AUTO_NUMBER, "row5", -5);

      expectedRows =
        createExpectedTable(
            createExpectedRow("id", 1, "data", "row1_mod", "num", -1),
            createExpectedRow("id", 2, "data", "row2", "num", 0),
            createExpectedRow("id", 3, "data", "row3", "num", 1),
            createExpectedRow("id", 4, "data", "row4", "num", 4),
            createExpectedRow("id", 5, "data", "row5", "num", 0));
      
      assertTable(expectedRows, table);

      assertNotNull(pkCursor.findRowByEntry(3));
      pkCursor.setCurrentRowValue(numCol, -10);

      assertEquals(createExpectedRow("id", 3, "data", "row3", "num", 0),
                   pkCursor.getCurrentRow());
      
      db.close();
    }
  }  
}
