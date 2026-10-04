package mchorse.bbs_mod.utils.repos;

public enum RepositoryOperation
{
    /* Operation ids are ordinals on the wire: append new entries only at the end. */
    LOAD, SAVE, RENAME, DELETE, KEYS, ADD_FOLDER, RENAME_FOLDER, DELETE_FOLDER, FILM_META, BACKUPS;
}
