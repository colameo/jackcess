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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.logging.Handler;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Describes which database pages a particular table uses
 * @author Tim McCune
 */
public class UsageMap
{
  
  private static final Log LOG = LogFactory.getLog(UsageMap.class);
  
  /** Inline map type */
  public static final byte MAP_TYPE_INLINE = 0x0;
  /** Reference map type, for maps that are too large to fit inline */
  public static final byte MAP_TYPE_REFERENCE = 0x1;
  
  /** Page number of the map declaration */
  private int _dataPageNum;
  /** Offset of the data page at which the usage map data starts */
  private int _startOffset;
  /** Offset of the data page at which the usage map declaration starts */
  private short _rowStart;
  /** Format of the database that contains this usage map */
  private JetFormat _format;
  /** First page that this usage map applies to */
  private int _startPage;
  /** bits representing page numbers used, offset from _startPage */
  private BitSet _pageNumbers = new BitSet();
  /** Buffer that contains the usage map declaration page */
  private ByteBuffer _dataBuffer;
  /** Used to read in pages */
  private PageChannel _pageChannel;
  /** modification count on the usage map, used to keep the iterators in
      sync */
  private int _modCount = 0;
  /** the current handler implementation for reading/writing the specific
      usage map type.  note, this may change over time. */
  private Handler _handler;
  
  /**
   * @param pageChannel Used to read in pages
   * @param dataBuffer Buffer that contains this map's declaration
   * @param pageNum Page number that this usage map is contained in
   * @param format Format of the database that contains this usage map
   * @param rowStart Offset at which the declaration starts in the buffer
   */
  private UsageMap(PageChannel pageChannel, ByteBuffer dataBuffer,
                   int pageNum, JetFormat format, short rowStart)
  throws IOException
  {
    _pageChannel = pageChannel;
    _dataBuffer = dataBuffer;
    _dataPageNum = pageNum;
    _format = format;
    _rowStart = rowStart;
    _dataBuffer.position((int) _rowStart + format.OFFSET_MAP_START);
    _startOffset = _dataBuffer.position();
    if (LOG.isDebugEnabled()) {
      LOG.debug("Usage map block:\n" + ByteUtil.toHexString(_dataBuffer, _rowStart,
          dataBuffer.limit() - _rowStart));
    }
  }

  /**
   * @param pageChannel Used to read in pages
   * @param pageNum Page number that this usage map is contained in
   * @param rowNum Number of the row on the page that contains this usage map
   * @param format Format of the database that contains this usage map
   * @return Either an InlineUsageMap or a ReferenceUsageMap, depending on
   *         which type of map is found
   */
  public static UsageMap read(PageChannel pageChannel, int pageNum,
                              byte rowNum, JetFormat format)
    throws IOException
  {
    ByteBuffer dataBuffer = pageChannel.createPageBuffer();
    pageChannel.readPage(dataBuffer, pageNum);
    short rowStart = Table.findRowStart(dataBuffer, rowNum, format);
    int rowEnd = Table.findRowEnd(dataBuffer, rowNum, format);
    dataBuffer.limit(rowEnd);    
    byte mapType = dataBuffer.get(rowStart);
    UsageMap rtn = new UsageMap(pageChannel, dataBuffer, pageNum, format,
                                rowStart);
    rtn.initHandler(mapType);
    return rtn;
  }

  private void initHandler(byte mapType)
    throws IOException
  {
    if (mapType == MAP_TYPE_INLINE) {
      _handler = new InlineHandler();
    } else if (mapType == MAP_TYPE_REFERENCE) {
      _handler = new ReferenceHandler();
    } else {
      throw new IOException("Unrecognized map type: " + mapType);
    }
  }
  
  public PageIterator iterator() {
    return new ForwardPageIterator();
  }

  public PageIterator reverseIterator() {
    return new ReversePageIterator();
  }
  
  protected short getRowStart() {
    return _rowStart;
  }
  
  protected void setStartOffset(int startOffset) {
    _startOffset = startOffset;
  }
  
  protected int getStartOffset() {
    return _startOffset;
  }
  
  protected ByteBuffer getDataBuffer() {
    return _dataBuffer;
  }
  
  protected int getDataPageNumber() {
    return _dataPageNum;
  }
  
  protected PageChannel getPageChannel() {
    return _pageChannel;
  }
  
  protected JetFormat getFormat() {
    return _format;
  }

  protected int getStartPage() {
    return _startPage;
  }

  protected void setStartPage(int newStartPage) {
    _startPage = newStartPage;
  }

  protected BitSet getPageNumbers() {
    return _pageNumbers;
  }
  
  /**
   * Read in the page numbers in this inline map
   */
  protected void processMap(ByteBuffer buffer, int pageIndex, int startPage) {
    int byteCount = 0;
    _startPage = startPage;
    while (buffer.hasRemaining()) {
      byte b = buffer.get();
      if(b != (byte)0) {
        for (int i = 0; i < 8; i++) {
          if ((b & (1 << i)) != 0) {
            int pageNumberOffset = (byteCount * 8 + i) +
              (pageIndex * _format.PAGES_PER_USAGE_MAP_PAGE);
            _pageNumbers.set(pageNumberOffset);
          }
        }
      }
      byteCount++;
    }
  }
  
  /**
   * Add a page number to this usage map
   */
  public void addPageNumber(int pageNumber) throws IOException {
    //Sanity check, only on in debug mode for performance considerations
    if (LOG.isDebugEnabled()) {
      int pageNumberOffset = pageNumber - _startPage;
      if((pageNumberOffset < 0) ||
         _pageNumbers.get(pageNumberOffset)) {
        throw new IOException("Page number " + pageNumber +
                              " already in usage map");
      }
    }
    ++_modCount;
    _handler.addOrRemovePageNumber(pageNumber, true);
  }
  
  /**
   * Remove a page number from this usage map
   */
  public void removePageNumber(int pageNumber) throws IOException {
    ++_modCount;
    _handler.addOrRemovePageNumber(pageNumber, false);
  }
  
  protected void updateMap(int absolutePageNumber, int relativePageNumber,
      int bitmask, ByteBuffer buffer, boolean add)
  {
    //Find the byte to apply the bitmask to
    int offset = relativePageNumber / 8;
    byte b = buffer.get(_startOffset + offset);
    //Apply the bitmask
    int pageNumberOffset = absolutePageNumber - _startPage;
    if (add) {
      b |= bitmask;
      _pageNumbers.set(pageNumberOffset);
    } else {
      b &= ~bitmask;
      _pageNumbers.clear(pageNumberOffset);
    }
    buffer.put(_startOffset + offset, b);
  }
  
  private void promoteInlineHandlerToReferenceHandler(int pageNumber)
    throws IOException
  {
    // FIXME writeme
//     int startPage = _startPage;
//     BitSet curPageNumbers = (BitSet)_pageNumbers.clone();
//     _pageNumbers.clear();
//     _startPage = 0;
    
  }
  
  public String toString() {
    StringBuilder builder = new StringBuilder("page numbers: [");
    for(PageIterator iter = iterator(); iter.hasNextPage(); ) {
      builder.append(iter.getNextPage());
      if(iter.hasNextPage()) {
        builder.append(", ");
      }
    }
    builder.append("]");
    return builder.toString();
  }
  
  private abstract class Handler
  {
    protected Handler() {
    }
    
    /**
     * @param pageNumber Page number to add or remove from this map
     * @param add True to add it, false to remove it
     */
    public abstract void addOrRemovePageNumber(int pageNumber, boolean add)
      throws IOException;
  }

  /**
   * Usage map whose map is written inline in the same page.  This type of map
   * can contain a maximum of 512 pages, and is always used for free space
   * maps.  It has a start page, which all page numbers in its map are
   * calculated as starting from.
   * @author Tim McCune
   */
  private class InlineHandler extends Handler
  {
    private InlineHandler()
      throws IOException
    {
      int startPage = getDataBuffer().getInt(getRowStart() + 1);
      processMap(getDataBuffer(), 0, startPage);
    }
  
    @Override
    public void addOrRemovePageNumber(int pageNumber, boolean add)
      throws IOException
    {
      int startPage = getStartPage();
      if (add && pageNumber < startPage) {
        throw new IOException("Can't add page number " + pageNumber +
                              " because it is less than start page " + startPage);
      }
      int relativePageNumber = pageNumber - startPage;
      ByteBuffer buffer = getDataBuffer();
      if ((!add && !getPageNumbers().get(relativePageNumber)) ||
          (add && (relativePageNumber >
                   (getFormat().USAGE_MAP_TABLE_BYTE_LENGTH * 8 - 1))))
      {
        // FIXME writeme
//         if(add) {
//           promoteInlineHandlerToReferenceHandler(pageNumber);
//           return;
//         }
        //Increase the start page to the current page and clear out the map.
        startPage = pageNumber;
        setStartPage(startPage);
        buffer.position(getRowStart() + 1);
        buffer.putInt(startPage);
        getPageNumbers().clear();
        if (!add) {
          for (int j = 0; j < getFormat().USAGE_MAP_TABLE_BYTE_LENGTH; j++) {
            buffer.put((byte) 0xff); //Fill bitmap with 1s
          }
          getPageNumbers().set(0, (getFormat().USAGE_MAP_TABLE_BYTE_LENGTH * 8)); //Fill our list with page numbers
        }
        getPageChannel().writePage(buffer, getDataPageNumber());
        relativePageNumber = pageNumber - startPage;
      }
      updateMap(pageNumber, relativePageNumber, 1 << (relativePageNumber % 8), buffer, add);
      //Write the updated map back to disk
      getPageChannel().writePage(buffer, getDataPageNumber());
    }
  }

  /**
   * Usage map whose map is written across one or more entire separate pages
   * of page type USAGE_MAP.  This type of map can contain 32736 pages per
   * reference page, and a maximum of 16 reference map pages for a total
   * maximum of 523776 pages (2 GB).
   * @author Tim McCune
   */
  private class ReferenceHandler extends Handler
  {
    /** Buffer that contains the current reference map page */ 
    private final TempPageHolder _mapPageHolder =
      TempPageHolder.newHolder(false);
  
    private ReferenceHandler()
      throws IOException
    {
      setStartOffset(getFormat().OFFSET_USAGE_MAP_PAGE_DATA);
      // there is no "start page" for a reference usage map, so we get an
      // extra page reference on top of the number of page references that fit
      // in the table
      int numPages = (getFormat().USAGE_MAP_TABLE_BYTE_LENGTH / 4) + 1;
      for (int i = 0; i < numPages; i++) {
        int mapPageNum = getDataBuffer().getInt(
            getRowStart() + getFormat().OFFSET_REFERENCE_MAP_PAGE_NUMBERS +
            (4 * i));
        if (mapPageNum > 0) {
          ByteBuffer mapPageBuffer =
            _mapPageHolder.setPage(getPageChannel(), mapPageNum);
          byte pageType = mapPageBuffer.get();
          if (pageType != PageTypes.USAGE_MAP) {
            throw new IOException("Looking for usage map at page " +
                                  mapPageNum + ", but page type is " +
                                  pageType);
          }
          mapPageBuffer.position(getFormat().OFFSET_USAGE_MAP_PAGE_DATA);
          processMap(mapPageBuffer, i, 0);
        }
      }
    }
  
    @Override
    public void addOrRemovePageNumber(int pageNumber, boolean add)
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
     * Create a new usage map page and update the map declaration with a
     * pointer to it.
     * @param pageIndex Index of the page reference within the map declaration 
     */
    private int createNewUsageMapPage(int pageIndex) throws IOException {
      ByteBuffer mapPageBuffer = _mapPageHolder.startNewPage(getPageChannel());
      mapPageBuffer.put(PageTypes.USAGE_MAP);
      mapPageBuffer.put((byte) 0x01);  //Unknown
      mapPageBuffer.putShort((short) 0); //Unknown
      for(int i = 0; i < mapPageBuffer.limit(); ++i) {
        byte b = mapPageBuffer.get(i);
      }
      int mapPageNum = getPageChannel().writeNewPage(mapPageBuffer);
      _mapPageHolder.finishNewPage(mapPageNum);
      getDataBuffer().putInt(calculateMapPagePointerOffset(pageIndex),
                             mapPageNum);
      getPageChannel().writePage(getDataBuffer(), getDataPageNumber());
      return mapPageNum;
    }
  
    private int calculateMapPagePointerOffset(int pageIndex) {
      return getRowStart() + getFormat().OFFSET_REFERENCE_MAP_PAGE_NUMBERS +
        (pageIndex * 4);
    }
  }
  
  
  /**
   * Utility class to iterate over the pages in the UsageMap.
   */
  public abstract class PageIterator
  {
    /** the next set page number bit */
    protected int _nextSetBit;
    /** the previous set page number bit */
    protected int _prevSetBit;
    /** the last read modification count on the UsageMap.  we track this so
        that the iterator can detect updates to the usage map while iterating
        and act accordingly */
    protected int _lastModCount;

    protected PageIterator() {
    }

    /**
     * @return {@code true} if there is another valid page, {@code false}
     *         otherwise.
     */
    public boolean hasNextPage() {
      if((_nextSetBit < 0) &&
         (_lastModCount != _modCount)) {
        // recheck the last page, in case more showed up
        if(_prevSetBit < 0) {
          // we were at the beginning
          reset();
        } else {
          _nextSetBit = _prevSetBit;
          getNextPage();
        }
      }
      return(_nextSetBit >= 0);
    }      
    
    /**
     * @return valid page number if there was another page to read,
     *         {@link PageChannel#INVALID_PAGE_NUMBER} otherwise
     */
    public abstract int getNextPage();

    /**
     * After calling this method, getNextPage will return the first page in the
     * map
     */
    public abstract void reset();
  }
  
  /**
   * Utility class to iterate forward over the pages in the UsageMap.
   */
  public class ForwardPageIterator extends PageIterator
  {
    private ForwardPageIterator() {
      reset();
    }
    
    @Override
    public int getNextPage() {
      if (hasNextPage()) {
        _lastModCount = _modCount;
        _prevSetBit = _nextSetBit;
        _nextSetBit = _pageNumbers.nextSetBit(_nextSetBit + 1);
        return _prevSetBit + _startPage;
      }
      return PageChannel.INVALID_PAGE_NUMBER;
    }

    @Override
    public final void reset() {
      _lastModCount = _modCount;
      _prevSetBit = -1;
      _nextSetBit = _pageNumbers.nextSetBit(0);
    }
  }
  
  /**
   * Utility class to iterate backward over the pages in the UsageMap.
   */
  public class ReversePageIterator extends PageIterator
  {
    private ReversePageIterator() {
      reset();
    }
    
    @Override
    public int getNextPage() {
      if(hasNextPage()) {
        _lastModCount = _modCount;
        _prevSetBit = _nextSetBit;
        --_nextSetBit;
        while(hasNextPage() && !_pageNumbers.get(_nextSetBit)) {
          --_nextSetBit;
        }
        return _prevSetBit + _startPage;
      }
      return PageChannel.INVALID_PAGE_NUMBER;
    }

    @Override
    public final void reset() {
      _lastModCount = _modCount;
      _prevSetBit = -1;
      _nextSetBit = _pageNumbers.length() - 1;
    }
  }

  
}
