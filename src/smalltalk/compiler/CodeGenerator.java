package smalltalk.compiler;

import org.antlr.symtab.ClassSymbol;
import org.antlr.symtab.Scope;
import org.antlr.symtab.Symbol;
//import org.antlr.symtab.Utils;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import smalltalk.compiler.symbols.*;

import java.util.ArrayList;
import java.util.List;

import static smalltalk.compiler.Bytecode.*;
import smalltalk.compiler.misc.Utils;

/** Fill STBlock, STMethod objects in Symbol table with bytecode,
 * {@link STCompiledBlock}.
 */
public class CodeGenerator extends SmalltalkBaseVisitor<Code> {
	public static final boolean dumpCode = false;

	public STClass currentClassScope;
	public Scope currentScope;

	/** With which compiler are we generating code? */
	public final Compiler compiler;

	public CodeGenerator(Compiler compiler) {
		this.compiler = compiler;
	}

	/** This and defaultResult() critical to getting code to bubble up the
	 *  visitor call stack when we don't implement every method.
	 */
	@Override
	protected Code aggregateResult(Code aggregate, Code nextResult) {
		if ( aggregate!=Code.None && aggregate != null) {
			if ( nextResult!=Code.None && nextResult != null) {
				return aggregate.join(nextResult);
			}
			return aggregate;
		}
		else {
			return nextResult;
		}
	}

	@Override
	protected Code defaultResult() {
		return Code.None;
	}

	@Override
	public Code visitFile(SmalltalkParser.FileContext ctx) {
		currentScope = compiler.symtab.GLOBALS;
		visitChildren(ctx);
		return Code.None;
	}

	@Override
	public Code visitClassDef(SmalltalkParser.ClassDefContext ctx) {
		currentClassScope = ctx.scope;
		pushScope(ctx.scope);
		Code code = visitChildren(ctx);
		popScope();
		currentClassScope = null;
		return code;
	}

    @Override public Code visitNamedMethod(SmalltalkParser.NamedMethodContext ctx) {
	    currentScope = ctx.scope;
	    return visitChildren(ctx);
	}

    @Override
    public Code visitOperatorMethod(SmalltalkParser.OperatorMethodContext ctx) {
        currentScope = ctx.scope;
        STBlock s = (STBlock) currentScope;
        s.args.add(ctx.bop().getText());
        //ctx.bop().opchar()
	    return visitChildren(ctx);
    }

    @Override
    public Code visitKeywordMethod(SmalltalkParser.KeywordMethodContext ctx) {
        currentScope = ctx.scope;
        STBlock b = (STBlock)currentScope;
        for (TerminalNode t : ctx.ID()){
            b.args.add(t.getText());
        }
	    return visitChildren(ctx);
    }

    @Override
    public Code visitMain(SmalltalkParser.MainContext ctx) {
	    if (ctx.body().getChildCount() == 0)
	        return null;
	    currentScope = ctx.scope;
	    currentClassScope = (STClass) ctx.scope.getEnclosingScope();
        STCompiledBlock stCompiledBlock = new STCompiledBlock((STClass) currentClassScope, (STBlock) currentScope);
        stCompiledBlock.blocks = new STCompiledBlock[((STBlock) currentScope).numNestedBlocks];
        ((STBlock) currentScope).compiledBlock = stCompiledBlock;

        Code nextResult = new Code();

        Code aggregate = visitChildren(ctx);
        nextResult.add(POP);
        nextResult.add(SELF);
        nextResult.add(RETURN);
        aggregate.join(nextResult);
        stCompiledBlock.bytecode = aggregate.bytes();
        return aggregate;
	}

    @Override
    public Code visitBlock(SmalltalkParser.BlockContext ctx) {
	    currentScope = ctx.scope;
        Code aggregate = visitChildren(ctx);
        currentScope = ctx.scope;
        STCompiledBlock stCompiledBlock = new STCompiledBlock((STClass) currentClassScope, (STBlock) currentScope);
        Code nextResult = new Code();
        nextResult.add(BLOCK_RETURN);
        aggregate.join(nextResult);
        stCompiledBlock.bytecode = aggregate.bytes();
        ((STBlock)currentScope).compiledBlock = stCompiledBlock;
        Scope s = currentScope.getEnclosingScope();
        while (!(s instanceof STMethod)){
            s = s.getEnclosingScope();
        }
        ((STBlock)s).compiledBlock.blocks[((STBlock) currentScope).index] = stCompiledBlock;
        Code c = Code.of(BLOCK).join(Utils.shortToBytes(((STBlock) currentScope).index));
        currentScope = currentScope.getEnclosingScope();
        return c;
	}

    @Override public Code visitBlockArgs(SmalltalkParser.BlockArgsContext ctx) {
        int c = ctx.getChildCount();
        if (currentScope instanceof STBlock)
            for (int i = 1; i < c; i++)
                ((STBlock)currentScope).args.add(ctx.getChild(i).getText());
        return visitChildren(ctx);
	}

    @Override public Code visitBinaryExpression(SmalltalkParser.BinaryExpressionContext ctx) {
	    int count = ctx.getChildCount();
	    Code nextResult = new Code();
	    /*for (int i = 0; i < count; i = i + 2){
	        nextResult.join(Code.of(PUSH_FIELD).join(Utils.shortToBytes(currentClassScope.getFieldIndex(ctx.getChild(i).getText()))));
        }*/
        if (count > 1){
            for (int i = 1; i < count; i = i + 2){
                if (i == 1)
                    nextResult.join(visit(ctx.getChild(i-1)));
                nextResult.join(visit(ctx.getChild(i+1)));
                nextResult.join(Code.of(SEND).join(Utils.shortToBytes(1)).join(Utils.shortToBytes(currentClassScope.stringTable.add(ctx.getChild(i).getText()))));
            }
            return nextResult;
        }

	    return visitChildren(ctx).join(nextResult);
	}

    @Override public Code visitKeywordSend(SmalltalkParser.KeywordSendContext ctx) {
        Code nextResult = new Code();
        Code aggregate = visitChildren(ctx);
        /*if (ctx.KEYWORD().size() == 2 && ctx.KEYWORD(0).getText().equals("to:") && ctx.KEYWORD(1).getText().equals("do:")){
            nextResult.join(Code.of(SEND).join(Utils.shortToBytes(ctx.args.size()))).join(Utils.shortToBytes(currentClassScope.stringTable.add("to:do:")));
        }
        else{
	        for (TerminalNode t : ctx.KEYWORD()){
                nextResult.join(Code.of(SEND).join(Utils.shortToBytes(ctx.args.size()))).join(Utils.shortToBytes(currentClassScope.stringTable.add(t.getText())));
            }
        }*/
        String str = "";
        for (TerminalNode t : ctx.KEYWORD())
            str += t.getText();
        nextResult.join(Code.of(SEND).join(Utils.shortToBytes(ctx.args.size()))).join(Utils.shortToBytes(currentClassScope.stringTable.add(str)));
        //nextResult.join(Code.of(POP));
        return aggregate.join(nextResult);
	}

    @Override public Code visitSuperKeywordSend(SmalltalkParser.SuperKeywordSendContext ctx) {

	    return visitChildren(ctx);
	}

    @Override public Code visitLiteral(SmalltalkParser.LiteralContext ctx) {
	    String str = ctx.getChild(0).getText();
	    if (str.equals("true")){
	        return Code.of(TRUE);
        }
        if (str.equals("false")){
	    	return Code.of(FALSE);
		}
        if (ctx.NUMBER() != null){
	        int i = Integer.parseInt(ctx.getText());
	        return Code.of(PUSH_INT).join(Utils.intToBytes(i));
        }
        if (ctx.STRING() != null){
            str = ctx.getText().substring(1, ctx.getText().length() - 1);
            return Code.of(PUSH_LITERAL).join(Utils.shortToBytes(currentClassScope.stringTable.add(str)));
        }
        if (str.equals("nil")){
            return Code.of(NIL);
        }
        if (str.equals("self")){
            return Code.of(SELF);
        }
	    return visitChildren(ctx);
	}

    @Override public Code visitAssign(SmalltalkParser.AssignContext ctx) {
	    STBlock s = (STBlock)currentScope;
	    int index = s.getLocalIndex(ctx.lvalue().getText());
	    Code nextResult;
	    if (index >= 0)
            nextResult = Code.of(STORE_LOCAL).join(Utils.shortToBytes(s.getRelativeScopeCount(ctx.lvalue().getText()))).join(Utils.shortToBytes(index));
	    else
	        nextResult = Code.of(STORE_FIELD).join(Utils.shortToBytes(currentClassScope.getFieldIndex(ctx.lvalue().getText())));
	    //nextResult.join(Code.of(POP));
	    return visitChildren(ctx).join(nextResult);
	}

    @Override public Code visitSendMessage(SmalltalkParser.SendMessageContext ctx) {

        return visitChildren(ctx);
	}

    @Override public Code visitUnaryMsgSend(SmalltalkParser.UnaryMsgSendContext ctx) {
	    return visitChildren(ctx).join(Code.of(SEND).join(Utils.shortToBytes(0)).join(Utils.shortToBytes(currentClassScope.stringTable.add(ctx.ID().getText()))));
	}

    @Override public Code visitUnarySuperMsgSend(SmalltalkParser.UnarySuperMsgSendContext ctx) {
	    Code nextResult = new Code();
	    nextResult.join(Code.of(SELF));
	    //if (ctx.ID().getText().equals("new"))
        nextResult.join(Code.of(SEND_SUPER)).join(Utils.shortToBytes(0)).join(Utils.shortToBytes(currentClassScope.stringTable.add(ctx.ID().getText())));

	    return visitChildren(ctx).join(nextResult);
	}

    @Override public Code visitUnaryIsPrimary(SmalltalkParser.UnaryIsPrimaryContext ctx) {
        STBlock s = (STBlock)currentScope;
	    if (ctx.primary().id() != null) {
            String str = ctx.getText();
            Symbol sym = s.resolve(str);
            if (str.equals("Transcript") || str.equals("Link")) {
                return visitChildren(ctx).join(Code.of(PUSH_GLOBAL)).join(Utils.shortToBytes(currentClassScope.stringTable.add(str)));
            }
            else{
                int index = s.getLocalIndex(ctx.getText());
                if (index == -1 && sym.getScope() instanceof STBlock){
                    index = ((STBlock) sym.getScope()).getLocalIndex(str);
                }
                if (index >= 0)
                    return visitChildren(ctx).join(Code.of(PUSH_LOCAL).join(Utils.shortToBytes(s.getRelativeScopeCount(str))).join(Utils.shortToBytes(index)));
                    //return visitChildren(ctx).join(Code.of(PUSH_LOCAL).join(Utils.shortToBytes(0)).join(Utils.shortToBytes(index)));
                else{
                    return visitChildren(ctx).join(Code.of(PUSH_FIELD).join(Utils.shortToBytes(currentClassScope.getFieldIndex(str))));}
            }
	    }
	    return visitChildren(ctx);
	}

    @Override public Code visitEmptyBody(SmalltalkParser.EmptyBodyContext ctx) {

	    return Code.of(NIL);
        //return visitChildren(ctx);
	}

    @Override public Code visitLocalVars(SmalltalkParser.LocalVarsContext ctx) {
        int c = ctx.getChildCount();
        if (currentScope instanceof STBlock)
            for (int i = 1; i < c -1; i++)
                ((STBlock)currentScope).locals.add(ctx.getChild(i).getText());
        return visitChildren(ctx);
	}

    @Override public Code visitOpchar(SmalltalkParser.OpcharContext ctx) {

	    /*if (currentScope instanceof STBlock) {
	        STBlock s = (STBlock) currentScope;
            s.args.add(ctx.getText());
        }*/

	    return visitChildren(ctx);
	}

    @Override public Code visitPrimitiveMethodBlock(SmalltalkParser.PrimitiveMethodBlockContext ctx) {
        STCompiledBlock stCompiledBlock = new STCompiledBlock((STClass) currentClassScope, (STBlock) currentScope);
        Code nextResult = new Code();
        STBlock b = (STBlock)currentScope;
        //stCompiledBlock.bytecode = code.bytes();
        ((STMethod)currentScope).compiledBlock = stCompiledBlock;
        /*Code aggregate = visitChildren(ctx);
        if (b.nargs() + b.nlocals() != 0)
            nextResult.add(POP);
        nextResult.add(SELF);
        nextResult.add(RETURN);
        aggregate = aggregateResult(aggregate, nextResult);
        if (b.nargs() + b.nlocals() != 0)
            stCompiledBlock.bytecode = aggregate.bytes();
        else
            stCompiledBlock.bytecode = nextResult.bytes();
        return aggregate;*/
        return visitChildren(ctx);
	}

    @Override
    public Code visitSmalltalkMethodBlock(SmalltalkParser.SmalltalkMethodBlockContext ctx){

	    STCompiledBlock stCompiledBlock = new STCompiledBlock((STClass) currentClassScope, (STBlock) currentScope);
        stCompiledBlock.blocks = new STCompiledBlock[((STBlock) currentScope).numNestedBlocks];
        Code nextResult = new Code();
        STBlock b = (STBlock)currentScope;
        //stCompiledBlock.bytecode = code.bytes();
	    ((STMethod)currentScope).compiledBlock = stCompiledBlock;
	    Code aggregate = visitChildren(ctx);
	    //if (b.nargs() + b.nlocals() != 0)
        nextResult.add(POP);
        nextResult.add(SELF);
        nextResult.add(RETURN);
	    aggregate = aggregateResult(aggregate, nextResult);
        if (aggregate.bytes().length  > 4)
	        stCompiledBlock.bytecode = aggregate.bytes();
        else
            stCompiledBlock.bytecode = Code.of(SELF).join(Code.of(RETURN)).bytes();
	    return aggregate;
    }

	public STCompiledBlock getCompiledPrimitive(STPrimitiveMethod primitive) {
		STCompiledBlock compiledMethod = new STCompiledBlock(currentClassScope, primitive);
		return compiledMethod;
	}

	/*
	All expressions have values. Must pop each expression value off, except
	last one, which is the block return value. So, we pop after each expr
	unless we're compiling a method block and the expr is not a ^expr. In a
	code block, we pop if we're not the last instruction of the block.

	localVars? expr ('.' expr)* '.'?
	 */
	@Override
	public Code visitFullBody(SmalltalkParser.FullBodyContext ctx) {
		// fill in
		//return visitChildren(ctx).join(Code.of(POP));
        Code localVars = new Code();
        Code stat = new Code();
        if (ctx.localVars() != null)
            localVars.join(visit(ctx.localVars()));
        int count = ctx.getChildCount();
        for (int i = 0; i < ctx.stat().size(); i++){

            stat.join(visit(ctx.stat().get(i)));
            if (i + 1 != ctx.stat().size())
                stat.join(Code.of(POP));
        }
        return localVars.join(stat);

        //return visitChildren(ctx);

	}

	@Override
	public Code visitReturn(SmalltalkParser.ReturnContext ctx) {
		Code e = visit(ctx.messageExpression());
		if ( compiler.genDbg ) {
			e = Code.join(e, dbg(ctx.start)); // put dbg after expression as that is when it executes
		}
		Code code = e.join(Compiler.method_return());
		return code;
	}

	public void pushScope(Scope scope) {
		currentScope = scope;
	}

	public void popScope() {
//		if ( currentScope.getEnclosingScope()!=null ) {
//		}
//		else {
//		}
		currentScope = currentScope.getEnclosingScope();
	}

	public int getLiteralIndex(String s) {
		return 0;
	}

	public Code dbgAtEndMain(Token t) {
		int charPos = t.getCharPositionInLine() + t.getText().length();
		return dbg(t.getLine(), charPos);
	}

	public Code dbgAtEndBlock(Token t) {
		int charPos = t.getCharPositionInLine() + t.getText().length();
		charPos -= 1; // point at ']'
		return dbg(t.getLine(), charPos);
	}

	public Code dbg(Token t) {
		return dbg(t.getLine(), t.getCharPositionInLine());
	}

	public Code dbg(int line, int charPos) {
		return Compiler.dbg(getLiteralIndex(compiler.getFileName()), line, charPos);
	}

	public Code store(String id) {
		return null;
	}

	public Code push(String id) {
		return null;
	}

	public Code sendKeywordMsg(ParserRuleContext receiver,
							   Code receiverCode,
							   List<SmalltalkParser.BinaryExpressionContext> args,
							   List<TerminalNode> keywords)
	{
		return null;
	}

	public String getProgramSourceForSubtree(ParserRuleContext ctx) {
		return null;
	}
}
