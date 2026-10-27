package org.edtp.edb.adaptors.entity_collision_optimizer;

/** The frame monitor must cover both acquiring and releasing a shared body-table lease. */
public interface EcoBatchOwner {
    void edb$owner(Object frame);
}
