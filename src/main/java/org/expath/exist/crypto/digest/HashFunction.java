/*
 *  Copyright (C) 2011 Claudius Teodorescu
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 *  $Id$
 */

package org.expath.exist.crypto.digest;

/**
 * Implements the crypto:hash() function for eXist.
 *
 * @author Claudius Teodorescu <claudius.teodorescu@gmail.com>
 */

import java.io.ByteArrayInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.Base64BinaryValueType;
import org.exist.xquery.value.BinaryValue;
import org.exist.xquery.value.BinaryValueFromInputStream;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.Type;

import ro.kuberam.libs.java.crypto.digest.Hash;

import static org.exist.xquery.FunctionDSL.*;
import static org.expath.exist.crypto.ExistExpathCryptoModule.*;

public class HashFunction extends BasicFunction {

    private static final Logger LOG = LogManager.getLogger(HashFunction.class);

    private static final String FS_HASH_NAME = "name";
    private static final FunctionParameterSequenceType FS_HASH_PARAM_DATA = param("data", Type.ANY_TYPE, "The data to be hashed.");
    private static final FunctionParameterSequenceType FS_HASH_PARAM_ALGORITHM = param("algorithm", Type.STRING, "The cryptographic hashing algorithm.");

    public static final FunctionSignature FS_HASH[] = functionSignatures(
        FS_HASH_NAME,
        "resulting hash value, as string.",
        returnsOptMany(Type.BYTE),
        arities(
            arity(
                FS_HASH_PARAM_DATA,
                FS_HASH_PARAM_ALGORITHM
            ),
            arity(
                FS_HASH_PARAM_DATA,
                FS_HASH_PARAM_ALGORITHM,
                param("encoding", Type.STRING, "The encoding of the output. The legal values are \"hex\" and \"base64\". The default value is \"base64\".")
            )
        )
    );

    public HashFunction(final XQueryContext context, final FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(final Sequence[] args, final Sequence contextSequence) throws XPathException {

        final int inputType = args[0].itemAt(0).getType();
        final String hashAlgorithm = args[1].getStringValue();
        String encoding = "base64";
        if (args.length == 3) {
            encoding = args[2].getStringValue();
        }

        final Sequence result;
        if (inputType == Type.STRING || inputType == Type.ELEMENT || inputType == Type.DOCUMENT) {
            try {
                result = new StringValue(Hash.hashString(args[0].getStringValue(), hashAlgorithm, encoding));
            } catch (final Exception ex) {
                throw new XPathException(ex.getMessage());
            }
        } else if (inputType == Type.BASE64_BINARY || inputType == Type.HEX_BINARY) {
            try {
                final byte[] binary = (byte[]) ((BinaryValue) args[0].itemAt(0)).toJavaObject(byte[].class);
                final BinaryValue data = BinaryValueFromInputStream.getInstance(context,
                        new Base64BinaryValueType(), new ByteArrayInputStream(binary));
                result = new StringValue(Hash.hashBinary(data.getInputStream(), hashAlgorithm, encoding));
            } catch (Exception ex) {
                throw new XPathException(ex.getMessage());
            }
        } else {
            result = Sequence.EMPTY_SEQUENCE;
        }
        // ValueSequence result = new ValueSequence();
        // for (int i = 0, il = resultBytes.length; i < il; i++) {
        // result.add(new IntegerValue(resultBytes[i]));
        // }

        return result;
    }
}