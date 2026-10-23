-- Schema only, exported read-only from installed Salt 1.19.1 (no user rows).
CREATE TABLE `Track` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `albumArtist` TEXT NOT NULL, `genre` TEXT NOT NULL, `year` INTEGER NOT NULL, `number` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, `playCount` INTEGER NOT NULL, `order` INTEGER NOT NULL, `path` TEXT NOT NULL, `readable` INTEGER NOT NULL, `size` INTEGER NOT NULL, `addedTime` INTEGER NOT NULL, `modifiedTime` INTEGER NOT NULL, `coverRevision` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `bitsPerSample` INTEGER NOT NULL, `sampleRate` INTEGER NOT NULL, `bitrate` REAL NOT NULL, `trackGain` REAL NOT NULL, `trackPeak` REAL NOT NULL, `albumGain` REAL NOT NULL, `albumPeak` REAL NOT NULL, `audioHash` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`));
CREATE TABLE `SyncScope` (
    `id` TEXT NOT NULL,
    `provider` TEXT NOT NULL,
    `status` TEXT NOT NULL,
    `containerIdentifier` TEXT NOT NULL,
    `environment` TEXT NOT NULL,
    `userRecordName` TEXT NOT NULL,
    `datasetId` TEXT NOT NULL,
    `recordZoneName` TEXT NOT NULL,
    `sessionId` TEXT NOT NULL,
    `engineStateData` BLOB NOT NULL,
    `lastSequenceNumber` INTEGER NOT NULL,
    `initialSyncPhase` TEXT NOT NULL,
    `initialSyncCursor` TEXT NOT NULL,
    `blockingReason` TEXT NOT NULL,
    PRIMARY KEY(`id`)
);
CREATE TABLE `SyncTrack` (
    `id` TEXT NOT NULL,
    `scopeId` TEXT NOT NULL,
    `syncId` TEXT NOT NULL,
    `title` TEXT NOT NULL,
    `artist` TEXT NOT NULL,
    `album` TEXT NOT NULL,
    `albumArtist` TEXT NOT NULL,
    `genre` TEXT NOT NULL,
    `year` INTEGER NOT NULL,
    `number` INTEGER NOT NULL,
    `isFavorite` INTEGER NOT NULL,
    `audioHash` TEXT NOT NULL,
    `duration` INTEGER NOT NULL,
    `primaryTrackId` TEXT,
    `playbackTrackId` TEXT,
    `bindingEvidenceData` BLOB NOT NULL,
    `mergeStateData` BLOB NOT NULL,
    PRIMARY KEY(`id`),
    FOREIGN KEY(`scopeId`) REFERENCES `SyncScope`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
    FOREIGN KEY(`primaryTrackId`) REFERENCES `Track`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
    FOREIGN KEY(`playbackTrackId`) REFERENCES `Track`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
);
CREATE TABLE "Playlist" (
    `id` TEXT NOT NULL,
    `title` TEXT NOT NULL,
    `coverModifiedTime` INTEGER NOT NULL,
    `isUserEditedCover` INTEGER NOT NULL,
    `description` TEXT NOT NULL,
    `createdTime` INTEGER NOT NULL,
    `order` INTEGER NOT NULL,
    `trackSort` INTEGER NOT NULL,
    `trackSortDescending` INTEGER NOT NULL,
    `scopeId` TEXT NOT NULL,
    `syncId` TEXT NOT NULL,
    `positionKey` BLOB NOT NULL,
    `mergeStateData` BLOB NOT NULL,
    PRIMARY KEY(`id`),
    FOREIGN KEY(`scopeId`) REFERENCES `SyncScope`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
);
CREATE TABLE "PlaylistTrack" (
    `playlistId` TEXT NOT NULL,
    `syncTrackId` TEXT NOT NULL,
    `addedTime` INTEGER NOT NULL,
    `order` INTEGER NOT NULL,
    `scopeId` TEXT NOT NULL,
    `syncId` TEXT NOT NULL,
    `isRemoved` INTEGER NOT NULL,
    `positionKey` BLOB NOT NULL,
    `mergeStateData` BLOB NOT NULL,
    PRIMARY KEY(`playlistId`, `syncTrackId`),
    FOREIGN KEY(`scopeId`, `playlistId`) REFERENCES "Playlist"(`scopeId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE,
    FOREIGN KEY(`scopeId`, `syncTrackId`) REFERENCES `SyncTrack`(`scopeId`, `id`) ON UPDATE NO ACTION ON DELETE RESTRICT
);
CREATE INDEX `index_Track_path` ON `Track` (`path`);
CREATE UNIQUE INDEX `index_Playlist_scopeId_syncId` ON "Playlist" (`scopeId`, `syncId`);
CREATE UNIQUE INDEX `index_Playlist_scopeId_id` ON "Playlist" (`scopeId`, `id`);
CREATE INDEX `index_Playlist_scopeId_positionKey` ON "Playlist" (`scopeId`, `positionKey`);
CREATE UNIQUE INDEX `index_SyncScope_containerIdentifier_environment_userRecordName_datasetId` ON `SyncScope` (`containerIdentifier`, `environment`, `userRecordName`, `datasetId`);
CREATE UNIQUE INDEX `index_SyncTrack_scopeId_syncId` ON `SyncTrack` (`scopeId`, `syncId`);
CREATE UNIQUE INDEX `index_SyncTrack_scopeId_id` ON `SyncTrack` (`scopeId`, `id`);
CREATE UNIQUE INDEX `index_SyncTrack_scopeId_primaryTrackId` ON `SyncTrack` (`scopeId`, `primaryTrackId`);
CREATE INDEX `index_SyncTrack_scopeId_audioHash` ON `SyncTrack` (`scopeId`, `audioHash`);
CREATE INDEX `index_SyncTrack_primaryTrackId` ON `SyncTrack` (`primaryTrackId`);
CREATE INDEX `index_SyncTrack_playbackTrackId` ON `SyncTrack` (`playbackTrackId`);
CREATE UNIQUE INDEX `index_PlaylistTrack_scopeId_syncId` ON `PlaylistTrack` (`scopeId`, `syncId`);
CREATE INDEX `index_PlaylistTrack_scopeId_playlistId_isRemoved_positionKey` ON `PlaylistTrack` (`scopeId`, `playlistId`, `isRemoved`, `positionKey`);
CREATE INDEX `index_PlaylistTrack_scopeId_syncTrackId` ON `PlaylistTrack` (`scopeId`, `syncTrackId`);
CREATE INDEX `index_Track_audioHash` ON `Track` (`audioHash`);
