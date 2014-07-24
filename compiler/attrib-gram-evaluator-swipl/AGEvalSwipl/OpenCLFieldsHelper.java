package AGEvalSwipl;

import java.util.ArrayList;
import java.util.HashSet;

import AGEval.IFace;
import AGEval.InvalidGrammarException;
import AGEvalSwipl.AGEvaluatorSwipl.Schedule;
import aleGrammar.ALEParser;
import aleGrammar.ALEParser.ExtendedVertex;

class OpenCLFieldsHelper {
	// The the placeholder FTL type name to use when we're dealing with one of the grammar tokens that we hold in a
	// enum in OpenCL/C++ code
	public static final String enum_type_name = "GrammarTokens";
	// The name of the num in OpenCL/C++ which holds the grammar tokens
	public static final String enum_name = "unionvariants";

	private ALEParser ast;
	@SuppressWarnings("unused")
	private Schedule sched;
	private HashSet<Field> fields;
	// This is a map of all the OpenCL monolithic buffers derived from our AST.
	// Its keys are the buffer name, and its values is the number of variables
	// held within the buffer..
	//private HashMap<String, Integer> ocl_buffers;
	private ArrayList<CLBuffer> buffers;


	public OpenCLFieldsHelper(ALEParser ast, Schedule sched) throws InvalidGrammarException {
		fields = new HashSet<Field>();
		buffers = new ArrayList<CLBuffer>();

		parseAstAndSchedule(ast, sched);

		// Add in refname and display by default. They have no specific class.
		addField(null, "display", enum_type_name);
		addField(null, "refname", enum_type_name);

		// Ditto rightSibling
		addField(null, "right_siblings", "NodeIndex");
		addField(null, "left_siblings", "NodeIndex");
		addField(null, "parent", "NodeIndex");
		addField(null, "id", "NodeIndex");
	}


	// Returns a map of: monolithic buffers derived from this AST -> number of
	// distinct fields stored in that buffer
	public ArrayList<CLBuffer> getOclBuffers() {
		return buffers;
	}

	public ArrayList<CLBuffer> getBuffers() {
		return buffers;
	}

	public HashSet<Field> getFields() {
		return fields;
	}


	// Called by constructor to generate Field objects for each variable in
	// a given AST
	private void parseAstAndSchedule(ALEParser ast, Schedule sched) throws InvalidGrammarException {
		this.ast = ast;

		// For each interface
		for (IFace interf : ast.interfaces) {
			ALEParser.ExtendedClass ec = ast.extendedClasses.get(interf);

			// Parse the positioned input fields (ignore positioning for now)
			for(String property : ec.positionedInputs.values()) {
				addField(interf, property);
			}

			// Parse the public unpositioned variables
			for(String property : interf.getPubAttributes().keySet()) {
				addField(interf, property);
			}

			// Parse the interface's public fields
			for(String property : interf.getPubFields().keySet()) {
				addField(interf, property);
			}
		}

		// Now go through the classes parsing their fields as well
		for (AGEval.Class c : ast.classes) {
			for (String property : c.getPrivFields().keySet()) {
				addField(c, property);
			}

			for (String property : c.getPrivAttributes().keySet()) {
				addField(c, property);
			}

			// Generate collections
			for (String child_field : c.getChildMappings().keySet()) {
				addField(c, "child_" + child_field + "_leftmost_child", "NodeIndex");
				addField(c, "child_" + child_field + "_count", "int");
			}

			// Generate sinks
			//HashSet<String> sinks = sched.reductions.sinks.get(c);
			for (String sink : sched.reductions.sinks.get(c)) {
				String sink_type;

				// Check if the property in question is actually from a child
				if(sink.contains("@") && !sink.contains("self@")) {
					String child_name = sink.split("@")[0];
					AGEval.IFace child_iface = c.getChildMappings().get(child_name);
					sink_type = astPropertyToOclTypeString(sink.split("@")[1], child_iface);
				} else {
					sink_type = astPropertyToOclTypeString(sink, c);
				}

				if(sink.contains("@") && !sink.contains("self@")) {
					sink = sink.replace("@", "_");
				}
				addField(c, sink + "_init", sink_type);
				addField(c, sink + "_last", sink_type);
			}
		}
	}


	// Finds the Field object corresponding a given FTL class and property name.
	// Returns null if can not find existing Field matching FTL data.
	// cls may be null.
	public Field findClField(AGEval.IFace cls, String property) {
		String clean_prop_name = property.toLowerCase();
		if(property.contains("@") && !property.contains("self@")) {
			clean_prop_name = property.replace("@", "_");
		}

		// Fields with null classes match all classes (handles refname, display)
		// The property may also come from the cls' interface
		for(Field field : fields) {
			if(field.ftlName.toLowerCase().equals(clean_prop_name) && (cls == null || field.getCls() == null || field.getCls() == cls || field.getCls() == cls.getInterface())) {
				return field;
			}
		}

		return null;
	}


	// Adds a given field to our list of fields, with explicitly given OpenCL type
	public Field addField(AGEval.IFace cls, String property, String ocl_type) throws InvalidGrammarException {
		Field new_field;

		// Check if this field already exists and, if so, don't re-add it
		new_field = findClField(cls, property);
		if(new_field != null) {
			return new_field;
		}

		new_field = new Field(cls, property, ocl_type);

		// TODO: Add support for 'maybe' types
		if(new_field.isMaybeType()) {
			System.err.println(new_field.getClName() + " is a maybe type");
			throw new InvalidGrammarException("'maybe' types are not yet implemented in the OpenCL backend. Go yell at Matt.");
		}

		assignBuffer(new_field);
		fields.add(new_field);

		return new_field;
	}

	// Adds a given field to our list of fields, inferring OpenCL type
	public Field addField(AGEval.IFace cls, String property) throws InvalidGrammarException {
		String type =  astPropertyToOclTypeString(property, cls);

		return addField(cls, property, type);
	}

	// Takes in a Field not yet assigned to a buffer, and places it the proper one (creating a new buffer if need be,)
	// and then writes data about that assignment to the Field itself, in addition to recording it in the Buffer.
	private void assignBuffer(Field field) throws InvalidGrammarException {
		// VBO HACK
		// Don't give VBO types a buffer
		if(field.getClType().contains("VertexAndColor")) {
			return;
		}

		if(field.getClBufferName() != null) {
			throw new InvalidGrammarException("");
		}

		String buffer_name = field.getClType().toLowerCase().replaceAll("[- *]", "").replaceAll(":", "_") + "_buffer_1";

		CLBuffer buffer = null;

		// Check to see if there's an existing suitable buffer already
		for(CLBuffer buf : buffers) {
			if(buf.getBuffer_name().equals(buffer_name)) {
				buffer = buf;
			}
		}

		// If no suitable buffer was found, create a new one
		if(buffer == null) {
			buffer = new CLBuffer(buffer_name, field.getClType(), 0);
			buffers.add(buffer);
		}

		// Assign this field to this buffer
		buffer.addField(field);
	}


	// Takes a String representing the FTL type of a field, returns a String
	// representing the OpenCL type.
	// Taken for CppGenerator
	public static String typeStringToOclType(String type) throws InvalidGrammarException {
		String lType = type.toLowerCase();

		if (lType.equals("int") || lType.equals("time") || lType.equals("color") || lType.equals("px")) {
			return "int";
		} else if (lType.equals("bool")) {
			// TODO: Implement C++ bool to OpenCL int casting routines for use in tree data transfers.
			// Until then, just throw an error if there's a bool.
			throw new InvalidGrammarException("Bool types not supported in OpenCL.");
		} else if(lType.equals("float")) {
			return "float";
		} else if(lType.equals("double")) {
			return "double";
		} else if(lType.equals("string") || lType.equals("std::string") || lType.equals("const char *")) {
			throw new InvalidGrammarException("String type not valid in OpenCL");
		} else if(lType.equals(enum_type_name.toLowerCase()) || lType.equals("displaytype") || lType.equals("refnametype")) {
			return enum_type_name;
		} else if (lType.equals("vbo")) {
			return "__global VertexAndColor*";
		} else {
			throw new InvalidGrammarException("Type " + type + " is not recognized by OpenCL generator. Can not translate to OpenCL type.");
		}
	}

	// Converts an FTL property's type to equivalent OpenCL type
	public String astPropertyToOclTypeString(String property, AGEval.IFace cls) throws InvalidGrammarException {
		// If the class doesn't have it, check its interface
		if(ast.extendedClasses.get(cls).extendedVertices.get(property) == null) {
			cls = cls.getInterface();
		}

		String ftl_type = ast.extendedClasses.get(cls).extendedVertices.get(property).strType;

		return typeStringToOclType(ftl_type);
	}


	///////////////////////////////////////////////////////////////////////////
	// Simple struct class to hold one data on a single field from the AST
	///////////////////////////////////////////////////////////////////////////
	class Field implements Comparable {
		// Can be null only in case of refname or display or may not actually
		// contain property (as in case of _leftmost_child)
		private AGEval.IFace cls;
		private String clBufferName;
		private int    clBufferPosition;
		private String clType;
		private String ftlName;

		public Field(IFace cls, String ftl_name, String opencl_type, String ocl_buffer_name, int ocl_buffer_position) {
			this.clBufferName = ocl_buffer_name;
			this.clBufferPosition = ocl_buffer_position;
			this.ftlName = ftl_name;
			this.clType = opencl_type;

			// display and refname have no class
			if(ftl_name.equals("display") || ftl_name.equals("refname")) {
				this.cls = null;
			} else {
				this.cls = cls;
			}
		}

		public Field(IFace cls,String ftl_name, String opencl_type) {
			this.clBufferName = null;
			this.clBufferPosition = -1;
			this.ftlName = ftl_name;
			this.clType = opencl_type;

			// display and refname have no class
			if(ftl_name.equals("display") || ftl_name.equals("refname")) {
				this.cls = null;
			} else {
				this.cls = cls;
			}
		}

		// Can be void if this field is not stored in a buffer (as in the case of extern types, which will one day be added)
		public String getClBufferName() {
			return clBufferName;
		}

		// Undefined if clBufferName is null (currently defaults to -1)
		public int getClBufferPosition() {
			return clBufferPosition;
		}

		public void setClBufferName(String clBufferName) {
			this.clBufferName = clBufferName;
		}

		public void setClBufferPosition(int clBufferPosition) {
			this.clBufferPosition = clBufferPosition;
		}

		public String getClName() {
			// VBO HACK
			if(getClType().contains("VertexAndColor")) {
				return ftlName;
			}

			String clean_prop_name =  ftlName.toLowerCase().replaceAll("-", "").replaceAll(" ", "");

			// Handle things like refname and rightSiblings
			if(cls == null) {
				if(ftlName.equals("display")) {
					return "displayname";
				}
				return clean_prop_name;
			}

			String clean_class_name =  cls.getName().toLowerCase().replaceAll("-", "").replaceAll(" ", "");

			return "fld_" + clean_class_name + "_" + clean_prop_name;
		}

		// This function returns the name to use when accessing this field as a rhs.
		// Main difference is that it will cast token enum types correctly
		public String getClRhsName() {
			String cast = "";
			if(clType != null && clType == OpenCLFieldsHelper.enum_type_name) {
				cast = "(enum " + OpenCLFieldsHelper.enum_name + ") ";
			}

			return cast + getClName();
		}

		public AGEval.IFace getCls() {
			return cls;
		}

		public String getFtlName() {
			return ftlName;
		}

		// FYI: OpenCL and C++ types need to match
		public String getClType() {
			return clType;
		}

		// TODO: source this from actual FlatCppGenerator
		public String getCppName() {
			return getClName();
		}

		public Boolean isMaybeType() {
			if(cls == null) {
				return false;
			}

			ALEParser.ExtendedClass ec = ast.extendedClasses.get(cls);
			ExtendedVertex v = ec.extendedVertices.get(ftlName);

			if(v == null) {
				return false;
			} else {
				return v.isMaybeType;
			}
		}

		@Override
		public int compareTo(Object o) {
			if (!(o instanceof Field)){
				return -1;
			} else {
				Field f = (Field) o;
				return (clBufferName == null ? "" : getClName()).compareTo(f.clBufferName == null ? "" : f.getClName());
				//return clBufferPosition - f.clBufferPosition;
			}
		}
	}

	///////////////////////////////////////////////////////////////////////////
	// Simple struct class to hold info on a single buffer
	///////////////////////////////////////////////////////////////////////////
	class CLBuffer {
		// TODO: A better design would have the buffers contain Fields
		private String buffer_name;
		private Integer num_fields;
		private String buffer_type;

		public CLBuffer(String buffer_name, String buffer_type, Integer num_fields) {
			super();
			this.buffer_name = buffer_name;
			this.buffer_type = buffer_type;
			this.num_fields = num_fields;
		}

		// Gets the name of the buffer (should be valid in both C++ and OpenCL
		// code)
		public String getBuffer_name() {
			return buffer_name;
		}

		// Gets the number of fields this buffer has packed into it
		public Integer getNum_fields() {
			return num_fields;
		}

		public void incrementNum_fields() {
			num_fields++;
		}

		public String getBuffer_type() {
			return buffer_type;
		}

		// Adds the given Field to this buffer and sets the Field's buffer information appropriately
		public void addField(Field field) {
			field.setClBufferName(getBuffer_name());
			field.setClBufferPosition(getNum_fields());
			incrementNum_fields();
		}

		//Converts enum types to int
		public String getPrimitiveBuffer_type(){
			if(buffer_type.equalsIgnoreCase("int") || buffer_type.equalsIgnoreCase("float") || buffer_type.equalsIgnoreCase("double")) {
				return buffer_type;
			} else {
				return "int";
			}
		}
	}
}
