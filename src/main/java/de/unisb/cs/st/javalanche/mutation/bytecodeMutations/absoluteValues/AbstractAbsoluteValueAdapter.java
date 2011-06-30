package de.unisb.cs.st.javalanche.mutation.bytecodeMutations.absoluteValues;

import static de.unisb.cs.st.javalanche.mutation.results.Mutation.MutationType.*;
import static org.objectweb.asm.Opcodes.*;

import java.util.Map;

import org.apache.log4j.Logger;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import de.unisb.cs.st.javalanche.mutation.bytecodeMutations.AbstractMutationAdapter;
import de.unisb.cs.st.javalanche.mutation.results.Mutation;

public abstract class AbstractAbsoluteValueAdapter extends
		AbstractMutationAdapter {

	private static final Logger logger = Logger
			.getLogger(AbstractAbsoluteValueAdapter.class);
	public static final String ABSOLUTE = "1";
	public static final String FAIL_ON_ZERO = "0";
	public static final String ABSOLUTE_NEGATIVE = "-1";


	public AbstractAbsoluteValueAdapter(MethodVisitor mv, String className,
			String methodName, Map<Integer, Integer> possibilities, String desc) {
		super(mv, className, methodName, possibilities, desc);
	}

	public void visitVarInsn(int opcode, int var) {
		Integer type = null;
		if (opcode == ILOAD) {
			type = INTEGER;
		} else if (opcode == LLOAD) {
			type = LONG;
		} else if (opcode == FLOAD) {
			type = FLOAT;
		} else if (opcode == DLOAD) {
			type = DOUBLE;
		}
		super.visitVarInsn(opcode, var);
		if (type != null) {
			mutateLocal(type);
		}
	}


	@Override
	public void visitFieldInsn(int opcode, String owner, String name,
			String desc) {

		Integer typeOp = null;
		if (opcode == GETSTATIC || opcode == GETFIELD) {
			Type type = Type.getType(desc);
			if (type.equals(Type.INT_TYPE)) {
				typeOp = INTEGER;
			} else if (type.equals(Type.LONG_TYPE)) {
				typeOp = LONG;
			} else if (type.equals(Type.FLOAT_TYPE)) {
				typeOp = FLOAT;
			} else if (type.equals(Type.DOUBLE_TYPE)) {
				typeOp = DOUBLE;
			}
		}
		super.visitFieldInsn(opcode, owner, name, desc);
		if (typeOp != null) {
			mutateLocal(typeOp);
		}

	}
	private void mutateLocal(Integer type) {
		Mutation mutation = new Mutation(className, getMethodName(),
				getLineNumber(), getPossibilityForLine(), ABSOLUT_VALUE);
		addPossibilityForLine();
		handleMutation(mutation, type);
	}

	protected abstract void handleMutation(Mutation mutation, Integer type);

}
