/* 
 * -------------------------------------------------------------------
 * This source code, its documentation and all appendant files
 * are protected by copyright law. All rights reserved.
 *
 * Copyright, 2003 - 2007
 * University of Konstanz, Germany
 * Chair for Bioinformatics and Information Mining (Prof. M. Berthold)
 * and KNIME GmbH, Konstanz, Germany
 *
 * You may not modify, publish, transmit, transfer or sell, reproduce,
 * create derivative works from, distribute, perform, display, or in
 * any way exploit any of the content, in whole or in part, except as
 * otherwise expressly permitted in writing by the copyright owner or
 * as specified in the license file distributed with this product.
 *
 * If you have any questions please contact the copyright holder:
 * website: www.knime.org
 * email: contact@knime.org
 * -------------------------------------------------------------------
 */
package org.knime.core.util;

/**
 * This exception is thrown by the {@link DuplicateChecker} if a duplicate key
 * has been detected.
 * 
 * @author Thorsten Meinl, University of Konstanz
 */
public class DuplicateKeyException extends RuntimeException {
    private final String m_key;
    
    /**
     * Creates a new exception with predefined message.
     * 
     * @param key the duplicate key
     */
    public DuplicateKeyException(final String key) {
        this("Duplicate key detected: \"" + key + "\"", key); 
    }
    
    /**
     * Creates a new exception.
     * @param message The exception message, may or may not include 
     * <code>key</code>
     * @param key the duplicate key
     */
    public DuplicateKeyException(final String message, final String key) {
        super(message);
        m_key = key;
    }
    
    /**
     * Returns the duplicate key.
     * 
     * @return the key
     */
    public String getKey() {
        return m_key;
    }
    
}
