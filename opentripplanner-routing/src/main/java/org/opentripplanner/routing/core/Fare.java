/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.routing.core;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlTransient;

import java.util.HashMap;
import java.util.List;
import java.util.LinkedList;

/**
 * Fare is a set of fares for different classes of users.
 *
 * addNote() and setNotes() are implemented so that a base "template" fare
 * may be used for creating a new fare with added notes.
 */
public class Fare {

    /**
     * The default FareType, containing regular, student and senior types,
     * along with bicycle fares.
     */
    public enum DefaultFareType implements FareType {
        BICYCLE (true   ),

        REGULAR (null   ),
        STUDENT (REGULAR),
        SENIOR  (REGULAR);

        private FareType parent = null;
        private boolean global = false;

        DefaultFareType(boolean global) {
            this.global = global;
        }

        DefaultFareType(FareType parent) {
            this.parent = parent;
        }

        public FareType getParent() {
            return parent;
        }

        public boolean isGlobal() {
            return global;
        }
    }

    /**
     * A mapping from {@link FareType} to {@link Money}.
     */
    @XmlTransient
    public HashMap<FareType, FareTypeCost> fares;

    @XmlElementWrapper(name = "fareTypes")
    @XmlElement(name = "fareType")
    public LinkedList<FareTypeCost> getFareTypes() {
        return new LinkedList<FareTypeCost>(fares.values());
    }

    /**
     * The relevant agency's id.
     */
    @XmlAttribute
    public String agencyId = null;

    /**
     * The relevant agency's name
     */
    @XmlAttribute
    public String agencyName = null;

    /**
     * The customer-facing name of the fare.
     */
    @XmlAttribute
    public String name = null;

    /**
     * Descriptions of the usage of the fare.
     */
    @XmlElementWrapper(name = "notes")
    @XmlElement(name = "note")
    public List<String> notes = null;

    public Fare() {
        fares = new HashMap<FareType, FareTypeCost>();
    }

    public Fare(String agencyId, String name) {
        fares = new HashMap<FareType, FareTypeCost>();
        this.agencyId = agencyId;
        this.name = name;
    }

    public Fare(Fare f) {
        fares = new HashMap<FareType, FareTypeCost>(f.fares);
        agencyId = f.agencyId;
        name = f.name;
    }

    public void addFare(FareType fareType, Money money) {
        fares.put(fareType, new FareTypeCost(fareType, money));
    }

    public void addFare(FareType fareType, WrappedCurrency currency, int cents) {
        fares.put(fareType, new FareTypeCost(fareType, new Money(currency, cents)));
    }

    public Money getFare(FareType type) {
        if(fares.containsKey(type)) {
            return fares.get(type).money;
        }
        return null;
    }

    /**
     * Copies the notes from <code>orig</code> to a copy of this Fare.
     *
     * @param orig notes to copy
     * @return a new fare containing the notes from <code>orig</code>
     */
    public Fare setNotes(Fare orig) {
        Fare f = new Fare(this);
        f.notes = orig.notes;
        return f;
    }

    /**
     * Add a note to Fare. If the fare doesn't yet have notes, than it returns a new
     * duplicate fare with the note added. Otherwise the original fare is returned.
     */
    public Fare addNote(String note) {
        if(notes == null) {
            Fare f = new Fare(this);
            f.notes = new LinkedList<String>();
            f.notes.add(note);
            return f;
        } else {
            notes.add(note);
            return this;
        }
    }

    public Fare setAgency(String agencyId) {
        this.agencyId = agencyId;
        return this;
    }

    public String toString() {
        StringBuffer buffer = new StringBuffer("Fare(");
        buffer.append(name);
        buffer.append(" [");
        if(notes != null) {
            for (String note : notes) {
                buffer.append(note);
            }
        }
        buffer.append("], ");

        for (FareTypeCost fare : fares.values()) {
            buffer.append("[");
            buffer.append(fare.toString());
            buffer.append("] ");
        }
        buffer.append(")");
        return buffer.toString();
    }
}
