/*
 * The MIT License (MIT)
 *
 * Copyright (c) Microsoft Corporation
 * Copyright (c) Ha Thach for Adafruit Industries
 * Copyright (c) Henry Gabryjelski
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

#include <string.h>
#include <stdio.h>
#include <assert.h>

#include "uf2.h"
#include "compile_date.h"

#include "esp_log.h"
#include "esp_partition.h"

//--------------------------------------------------------------------+
//
//--------------------------------------------------------------------+

#define STATIC_ASSERT(_exp) _Static_assert(_exp, "static assert failed")

typedef struct {
    uint8_t JumpInstruction[3];
    uint8_t OEMInfo[8];
    uint16_t SectorSize;
    uint8_t SectorsPerCluster;
    uint16_t ReservedSectors;
    uint8_t FATCopies;
    uint16_t RootDirectoryEntries;
    uint16_t TotalSectors16;
    uint8_t MediaDescriptor;
    uint16_t SectorsPerFAT;
    uint16_t SectorsPerTrack;
    uint16_t Heads;
    uint32_t HiddenSectors;
    uint32_t TotalSectors32;
    uint8_t PhysicalDriveNum;
    uint8_t Reserved;
    uint8_t ExtendedBootSig;
    uint32_t VolumeSerialNumber;
    uint8_t VolumeLabel[11];
    uint8_t FilesystemIdentifier[8];
} __attribute__((packed)) FAT_BootBlock;

typedef struct {
    char name[8];
    char ext[3];
    uint8_t attrs;
    uint8_t reserved;
    uint8_t createTimeFine;
    uint16_t createTime;
    uint16_t createDate;
    uint16_t lastAccessDate;
    uint16_t highStartCluster;
    uint16_t updateTime;
    uint16_t updateDate;
    uint16_t startCluster;
    uint32_t size;
} __attribute__((packed)) DirEntry;
STATIC_ASSERT(sizeof(DirEntry) == 32);

struct TextFile {
  char const name[11];
  char const *content;
};


//--------------------------------------------------------------------+
//
//--------------------------------------------------------------------+

#define BPB_SECTOR_SIZE           ( 512)
#define BPB_SECTORS_PER_CLUSTER   (   1)
#define BPB_RESERVED_SECTORS      (   1)
#define BPB_NUMBER_OF_FATS        (   2)
#define BPB_ROOT_DIR_ENTRIES      (  64)
#define BPB_TOTAL_SECTORS         CFG_UF2_NUM_BLOCKS
#define BPB_MEDIA_DESCRIPTOR_BYTE (0xF8)
#define FAT_ENTRY_SIZE            (2)
#define FAT_ENTRIES_PER_SECTOR    (BPB_SECTOR_SIZE / FAT_ENTRY_SIZE)
// NOTE: MS specification explicitly allows FAT to be larger than necessary
#define BPB_SECTORS_PER_FAT       ( (BPB_TOTAL_SECTORS / FAT_ENTRIES_PER_SECTOR) + \
                                   ((BPB_TOTAL_SECTORS % FAT_ENTRIES_PER_SECTOR) ? 1 : 0))
#define DIRENTRIES_PER_SECTOR     (BPB_SECTOR_SIZE/sizeof(DirEntry))
#define ROOT_DIR_SECTOR_COUNT     (BPB_ROOT_DIR_ENTRIES/DIRENTRIES_PER_SECTOR)

STATIC_ASSERT(BPB_SECTOR_SIZE                              ==       512); // GhostFAT does not support other sector sizes (currently)
STATIC_ASSERT(BPB_SECTORS_PER_CLUSTER                      ==         1); // GhostFAT presumes one sector == one cluster (for simplicity)
STATIC_ASSERT(BPB_NUMBER_OF_FATS                           ==         2); // FAT highest compatibility
STATIC_ASSERT(sizeof(DirEntry)                             ==        32); // FAT requirement
STATIC_ASSERT(BPB_SECTOR_SIZE % sizeof(DirEntry)           ==         0); // FAT requirement
STATIC_ASSERT(BPB_ROOT_DIR_ENTRIES % DIRENTRIES_PER_SECTOR ==         0); // FAT requirement
STATIC_ASSERT(BPB_SECTOR_SIZE * BPB_SECTORS_PER_CLUSTER    <= (32*1024)); // FAT requirement (64k+ has known compatibility problems)
STATIC_ASSERT(FAT_ENTRIES_PER_SECTOR                       ==       256); // FAT requirement

#define STR0(x) #x
#define STR(x) STR0(x)

#define UF2_VERSION "0.0.0"
#define UF2_PRODUCT_NAME "Espressif saola"
#define UF2_BOARD_ID  "adafruit-test-board"
#define UF2_VOLUME_LABEL "ESP32SBOOT"
#define UF2_INDEX_URL "https://adafruit.com"

#define UF2_ARRAY_SIZE(_arr)    ( sizeof(_arr) / sizeof(_arr[0]) )

char infoUf2File[128*3] =
    "UF2 Bootloader " UF2_VERSION "\r\n"
    "Model: " UF2_PRODUCT_NAME "\r\n"
    "Board-ID: " UF2_BOARD_ID "\r\n";

const char indexFile[] =
    "<!doctype html>\n"
    "<html>"
    "<body>"
    "<script>\n"
    "location.replace(\"" UF2_INDEX_URL "\");\n"
    "</script>"
    "</body>"
    "</html>\n";

static struct TextFile const info[] = {
    {.name = "INFO_UF2TXT", .content = infoUf2File},
    {.name = "INDEX   HTM", .content = indexFile},

    // current.uf2 must be the last element and its content must be NULL
    {.name = "CURRENT UF2", .content = NULL},
};
STATIC_ASSERT(UF2_ARRAY_SIZE(infoUf2File) < BPB_SECTOR_SIZE); // GhostFAT requires files to fit in one sector
STATIC_ASSERT(UF2_ARRAY_SIZE(indexFile)   < BPB_SECTOR_SIZE); // GhostFAT requires files to fit in one sector

#define NUM_FILES          (UF2_ARRAY_SIZE(info))
#define NUM_DIRENTRIES     (NUM_FILES + 1) // Code adds volume label as first root directory entry
#define REQUIRED_ROOT_DIRECTORY_SECTORS ( ((NUM_DIRENTRIES+1) / DIRENTRIES_PER_SECTOR) + \
                                         (((NUM_DIRENTRIES+1) % DIRENTRIES_PER_SECTOR) ? 1 : 0))
STATIC_ASSERT(ROOT_DIR_SECTOR_COUNT >= REQUIRED_ROOT_DIRECTORY_SECTORS);         // FAT requirement -- Ensures BPB reserves sufficient entries for all files
STATIC_ASSERT(NUM_DIRENTRIES < (DIRENTRIES_PER_SECTOR * ROOT_DIR_SECTOR_COUNT)); // FAT requirement -- end directory with unused entry
STATIC_ASSERT(NUM_DIRENTRIES < BPB_ROOT_DIR_ENTRIES);                            // FAT requirement -- Ensures BPB reserves sufficient entries for all files
STATIC_ASSERT(NUM_DIRENTRIES < DIRENTRIES_PER_SECTOR); // GhostFAT bug workaround -- else, code overflows buffer

#define NUM_SECTORS_IN_DATA_REGION (BPB_TOTAL_SECTORS - BPB_RESERVED_SECTORS - (BPB_NUMBER_OF_FATS * BPB_SECTORS_PER_FAT) - ROOT_DIR_SECTOR_COUNT)
#define CLUSTER_COUNT              (NUM_SECTORS_IN_DATA_REGION / BPB_SECTORS_PER_CLUSTER)

// Ensure cluster count results in a valid FAT16 volume!
STATIC_ASSERT( CLUSTER_COUNT >= 0x0FF5 && CLUSTER_COUNT < 0xFFF5 );

// Many existing FAT implementations have small (1-16) off-by-one style errors
// So, avoid being within 32 of those limits for even greater compatibility.
STATIC_ASSERT( CLUSTER_COUNT >= 0x1015 && CLUSTER_COUNT < 0xFFD5 );


#define UF2_FIRMWARE_BYTES_PER_SECTOR 256
#define UF2_SECTORS        (_part_ota0->size / UF2_FIRMWARE_BYTES_PER_SECTOR)
#define UF2_SIZE           (UF2_SECTORS * BPB_SECTOR_SIZE)


#define USER_FLASH_START   0


#define UF2_FIRST_SECTOR   ((NUM_FILES + 1) * BPB_SECTORS_PER_CLUSTER) // WARNING -- code presumes each non-UF2 file content fits in single sector
#define UF2_LAST_SECTOR    ((UF2_FIRST_SECTOR + UF2_SECTORS - 1) * BPB_SECTORS_PER_CLUSTER)

#define FS_START_FAT0_SECTOR      BPB_RESERVED_SECTORS
#define FS_START_FAT1_SECTOR      (FS_START_FAT0_SECTOR + BPB_SECTORS_PER_FAT)
#define FS_START_ROOTDIR_SECTOR   (FS_START_FAT1_SECTOR + BPB_SECTORS_PER_FAT)
#define FS_START_CLUSTERS_SECTOR  (FS_START_ROOTDIR_SECTOR + ROOT_DIR_SECTOR_COUNT)

//--------------------------------------------------------------------+
//
//--------------------------------------------------------------------+

static FAT_BootBlock const BootBlock = {
    .JumpInstruction      = {0xeb, 0x3c, 0x90},
    .OEMInfo              = "UF2 UF2 ",
    .SectorSize           = BPB_SECTOR_SIZE,
    .SectorsPerCluster    = BPB_SECTORS_PER_CLUSTER,
    .ReservedSectors      = BPB_RESERVED_SECTORS,
    .FATCopies            = BPB_NUMBER_OF_FATS,
    .RootDirectoryEntries = BPB_ROOT_DIR_ENTRIES,
    .TotalSectors16       = (BPB_TOTAL_SECTORS > 0xFFFF) ? 0 : BPB_TOTAL_SECTORS,
    .MediaDescriptor      = BPB_MEDIA_DESCRIPTOR_BYTE,
    .SectorsPerFAT        = BPB_SECTORS_PER_FAT,
    .SectorsPerTrack      = 1,
    .Heads                = 1,
    .TotalSectors32       = (BPB_TOTAL_SECTORS > 0xFFFF) ? BPB_TOTAL_SECTORS : 0,
    .PhysicalDriveNum     = 0x80, // to match MediaDescriptor of 0xF8
    .ExtendedBootSig      = 0x29,
    .VolumeSerialNumber   = 0x00420042,
    .VolumeLabel          = UF2_VOLUME_LABEL,
    .FilesystemIdentifier = "FAT16   ",
};

// uf2 will always write to ota0 partition
static esp_partition_t const * _part_ota0 = NULL;

//--------------------------------------------------------------------+
//
//--------------------------------------------------------------------+

static inline bool is_uf2_block (UF2_Block const *bl)
{
  return (bl->magicStart0 == UF2_MAGIC_START0) &&
         (bl->magicStart1 == UF2_MAGIC_START1) &&
         (bl->magicEnd == UF2_MAGIC_END) &&
         (bl->flags & UF2_FLAG_FAMILYID) &&
         !(bl->flags & UF2_FLAG_NOFLASH);
}

void uf2_init(void)
{
  _part_ota0 = esp_partition_find_first(ESP_PARTITION_TYPE_APP, ESP_PARTITION_SUBTYPE_APP_OTA_0, NULL);
  assert(_part_ota0 != NULL);

  PRINT_INT(_part_ota0->type);
  PRINT_HEX(_part_ota0->subtype);
  PRINT_HEX(_part_ota0->address);
  PRINT_HEX(_part_ota0->size);
  PRINT_INT(_part_ota0->encrypted);
}

/*------------------------------------------------------------------*/
/* Read CURRENT.UF2
 *------------------------------------------------------------------*/
void padded_memcpy (char *dst, char const *src, int len)
{
  for ( int i = 0; i < len; ++i )
  {
    if ( *src ) {
      *dst = *src++;
    } else {
      *dst = ' ';
    }
    dst++;
  }
}

void uf2_read_block (uint32_t block_no, uint8_t *data)
{
  memset(data, 0, BPB_SECTOR_SIZE);
  uint32_t sectionIdx = block_no;

  if ( block_no == 0 )
  {
    // Requested boot block
    memcpy(data, &BootBlock, sizeof(BootBlock));
    data[510] = 0x55;    // Always at offsets 510/511, even when BPB_SECTOR_SIZE is larger
    data[511] = 0xaa;    // Always at offsets 510/511, even when BPB_SECTOR_SIZE is larger
  }
  else if ( block_no < FS_START_ROOTDIR_SECTOR )
  {
    // Requested FAT table sector
    sectionIdx -= FS_START_FAT0_SECTOR;

    // second FAT is same as the first...
    if ( sectionIdx >= BPB_SECTORS_PER_FAT ) sectionIdx -= BPB_SECTORS_PER_FAT;

    if ( sectionIdx == 0 )
    {
      // first FAT entry must match BPB MediaDescriptor
      data[0] = BPB_MEDIA_DESCRIPTOR_BYTE;
      // WARNING -- code presumes only one NULL .content for .UF2 file
      //            and all non-NULL .content fit in one sector
      //            and requires it be the last element of the array
      uint32_t const end = (NUM_FILES * FAT_ENTRY_SIZE) + (2 * FAT_ENTRY_SIZE);
      for ( uint32_t i = 1; i < end; ++i ) data[i] = 0xff;
    }

    for ( uint32_t i = 0; i < FAT_ENTRIES_PER_SECTOR; ++i )
    {
      // Generate the FAT chain for the firmware "file"
      uint32_t v = (sectionIdx * FAT_ENTRIES_PER_SECTOR) + i;

      if ( UF2_FIRST_SECTOR <= v && v <= UF2_LAST_SECTOR )
      {
        ((uint16_t*) (void*) data)[i] = v == UF2_LAST_SECTOR ? 0xffff : v + 1;
      }
    }
  }
  else if ( block_no < FS_START_CLUSTERS_SECTOR )
  {
    // Requested root directory sector

    sectionIdx -= FS_START_ROOTDIR_SECTOR;

    DirEntry *d = (void*) data;
    int remainingEntries = DIRENTRIES_PER_SECTOR;
    if ( sectionIdx == 0 )
    {
      // volume label first
      // volume label is first directory entry
      padded_memcpy(d->name, (char const*) BootBlock.VolumeLabel, 11);
      d->attrs = 0x28;
      d++;
      remainingEntries--;
    }

    for ( uint32_t i = DIRENTRIES_PER_SECTOR * sectionIdx;
        remainingEntries > 0 && i < NUM_FILES;
        i++, d++ )
    {
      // WARNING -- code presumes all but last file take exactly one sector
      uint16_t startCluster = i + 2;

      struct TextFile const *inf = &info[i];
      padded_memcpy(d->name, inf->name, 11);
      d->createTimeFine = __SECONDS_INT__ % 2 * 100;
      d->createTime = __DOSTIME__;
      d->createDate = __DOSDATE__;
      d->lastAccessDate = __DOSDATE__;
      d->highStartCluster = startCluster >> 16;
      d->updateTime = __DOSTIME__;
      d->updateDate = __DOSDATE__;
      d->startCluster = startCluster & 0xFFFF;
      d->size = (inf->content ? strlen(inf->content) : UF2_SIZE);
    }
  }
  else if ( block_no < BPB_TOTAL_SECTORS )
  {
    sectionIdx -= FS_START_CLUSTERS_SECTOR;
    if ( sectionIdx < NUM_FILES - 1 )
    {
      memcpy(data, info[sectionIdx].content, strlen(info[sectionIdx].content));
    }
    else
    {
      // generate the UF2 file data on-the-fly
      sectionIdx -= NUM_FILES - 1;
      uint32_t addr = USER_FLASH_START + (sectionIdx * UF2_FIRMWARE_BYTES_PER_SECTOR);
      if ( addr < CFG_UF2_FLASH_SIZE )
      {
        UF2_Block *bl = (void*) data;
        bl->magicStart0 = UF2_MAGIC_START0;
        bl->magicStart1 = UF2_MAGIC_START1;
        bl->magicEnd = UF2_MAGIC_END;
        bl->blockNo = sectionIdx;
        bl->numBlocks = UF2_SECTORS;
        bl->targetAddr = addr;
        bl->payloadSize = UF2_FIRMWARE_BYTES_PER_SECTOR;
        bl->flags = UF2_FLAG_FAMILYID;
        bl->familyID = CFG_UF2_FAMILY_ID;

        esp_partition_read(_part_ota0, addr, bl->data, bl->payloadSize);
      }
    }

  }
}

/*------------------------------------------------------------------*/
/* Write UF2
 *------------------------------------------------------------------*/

/**
 * Write an uf2 block wrapped by 512 sector.
 * @return number of bytes processed, only 3 following values
 *  -1 : if not an uf2 block
 * 512 : write is successful (BPB_SECTOR_SIZE == 512)
 *   0 : is busy with flashing, tinyusb stack will call write_block again with the same parameters later on
 */
int uf2_write_block (uint32_t block_no, uint8_t *data, WriteState *state)
{
  UF2_Block *bl = (void*) data;

  if ( !is_uf2_block(bl) ) return -1;

  if (bl->familyID == CFG_UF2_FAMILY_ID)
  {

  }

  //------------- Update written blocks -------------//
  if ( bl->numBlocks )
  {
    // Update state num blocks if needed
    if ( state->numBlocks != bl->numBlocks )
    {
      if ( bl->numBlocks >= MAX_BLOCKS || state->numBlocks )
        state->numBlocks = 0xffffffff;
      else
        state->numBlocks = bl->numBlocks;
    }

    if ( bl->blockNo < MAX_BLOCKS )
    {
      uint8_t const mask = 1 << (bl->blockNo % 8);
      uint32_t const pos = bl->blockNo / 8;

      // only increase written number with new write (possibly prevent overwriting from OS)
      if ( !(state->writtenMask[pos] & mask) )
      {
        state->writtenMask[pos] |= mask;
        state->numWritten++;
      }

      // flush last blocks
      // TODO numWritten can be smaller than numBlocks if return early
      if ( state->numWritten >= state->numBlocks )
      {
        //flash_nrf5x_flush(true);
      }
    }
  }

  return BPB_SECTOR_SIZE;
}
