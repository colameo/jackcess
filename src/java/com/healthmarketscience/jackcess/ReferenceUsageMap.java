/*
Copyright (c) 2005 Health Market Science, Inc.

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

You can contact Health Market Science at info@healthmarketscience.com
or at the following address:

Health Market Science
2700 Horizon Drive
Suite 200
King of Prussia, PA 19406
*/

package com.healthmarketscience.jackcess;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Usage map whose map is written across one or more entire separate pages of
 * page type USAGE_MAP.  This type of map can contain 32736 pages per reference
 * page, and a maximum of 16 reference map pages for a total maximum of 523776
 * pages (2 GB).
 * @author Tim McCune
 */
public class ReferenceUsageMap extends UsageMap {
  
  /** Buffer that contains the current reference map page */ 
  private final TempPageHolder _mapPageHolder =
    TempPageHolder.newHolder(false);
  
  /**
   * @param pageChannel Used to read in pages
   * @param dataBuffer Buffer that contains this map's declaration
   * @param pageNum Page number that this usage map is contained in
   * @param format Format of the database that contains this usage map
   * @param rowStart Offset at which the declaration starts in the buffer
   */
  public ReferenceUsageMap(PageChannel pageChannel, ByteBuffer dataBuffer,
      int pageNum, JetFormat format, short rowStart)
  throws IOException
  {
    super(pageChannel, dataBuffer, pageNum, format, rowStart);
    for (int i = 0; i < 17; i++) {
      int mapPageNum = dataBuffer.getInt(getRowStart() +
          format.OFFSET_REFERENCE_MAP_PAGE_NUMBERS + (4 * i));
      if (mapPageNum > 0) {
        ByteBuffer mapPageBuffer =
          _mapPageHolder.setPage(pageChannel, mapPageNum);
        byte pageType = mapPageBuffer.get();
        if (pageType != PageTypes.USAGE_MAP) {
          throw new IOException("Looking for usage map at page " + mapPageNum +
              ", but page type is " + pageType);
        }
        mapPageBuffer.position(format.OFFSET_USAGE_MAP_PAGE_DATA);
        setStartOffset(mapPageBuffer.position());
        processMap(mapPageBuffer, i, 0);
      }
    }
  }
  
  //Javadoc copied from UsageMap
  protected void addOrRemovePageNumber(final int pageNumber, boolean add)
  throws IOException
  {
    int pageIndex = (int) Math.floor(pageNumber / getFormat().PAGES_PER_USAGE_MAP_PAGE);
    int mapPageNum = getDataBuffer().getInt(calculateMapPagePointerOffset(pageIndex));
    if(mapPageNum <= 0) {
      //Need to create a new usage map page
      mapPageNum  = createNewUsageMapPage(pageIndex);
    }
    ByteBuffer mapPageBuffer = _mapPageHolder.setPage(getPageChannel(),
                                                      mapPageNum);
    updateMap(pageNumber, pageNumber - (getFormat().PAGES_PER_USAGE_MAP_PAGE * pageIndex),
        1 << ((pageNumber - (getFormat().PAGES_PER_USAGE_MAP_PAGE * pageIndex)) % 8),
        mapPageBuffer, add);
    getPageChannel().writePage(mapPageBuffer, mapPageNum);
  }
  
  /**
   * Create a new usage map page and update the map declaration with a pointer
   *    to it.
   * @param pageIndex Index of the page reference within the map declaration 
   */
  private int createNewUsageMapPage(int pageIndex) throws IOException {
    ByteBuffer mapPageBuffer = _mapPageHolder.startNewPage(getPageChannel());
    mapPageBuffer.put(PageTypes.USAGE_MAP);
    mapPageBuffer.put((byte) 0x01);  //Unknown
    mapPageBuffer.putShort((short) 0); //Unknown
    int mapPageNum = getPageChannel().writeNewPage(mapPageBuffer);
    _mapPageHolder.finishNewPage(mapPageNum);
    getDataBuffer().putInt(calculateMapPagePointerOffset(pageIndex),
                           mapPageNum);
    getPageChannel().writePage(getDataBuffer(), getDataPageNumber());
    return mapPageNum;
  }
  
  private int calculateMapPagePointerOffset(int pageIndex) {
    return getRowStart() + getFormat().OFFSET_REFERENCE_MAP_PAGE_NUMBERS + (pageIndex * 4);
  }
  
}
