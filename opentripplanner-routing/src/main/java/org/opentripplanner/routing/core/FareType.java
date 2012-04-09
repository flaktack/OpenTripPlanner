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

/**
 * FareTypes allow a Fare to have multiple prices, say a regular and a student price.
 */

public interface FareType {

    /**
     * A FareType which mas be used if a Fare doesn't have a price
     * for this FareType. As an example, if a fare doesn't have a student price,
     * than the regular price may be used for calculating the total fare.
     */
    public FareType getParent();

    /**
     * Is the FareType "global". When calculating the total cost of a itinerary,
     * should it be added to all other FareTypes? When traveling with a bicycle which
     * needs an extra ticket in addition to the normal regular/student/... fare, then
     * the bicycle fare is global since it needs to be added to the other FareTypes
     * cost.
     */
    public boolean isGlobal();
}
