-- What the guide said about a recorded program (.claude/plans/dvr.md → TV, the redesign), as JSON
-- (RecordingDetails): the guide drops a program once it has aired, and the Recordings page still shows it.
ALTER TABLE recording
    ADD COLUMN details TEXT NULL;
