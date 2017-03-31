package scala.xml.quote
package internal

import scala.language.implicitConversions
import scala.reflect.macros.blackbox
import scala.xml.quote.internal.{parsed => p}

trait Liftables {
  val c: blackbox.Context
  import c.universe._

  val args: List[Tree]

  val sx = q"_root_.scala.xml"

  implicit val liftNode: Liftable[p.Node] =
    Liftable {
      case n: p.Group     => liftGroup(n)
      case n: p.Elem      => liftElem(n)
      case n: p.Text      => liftText(n)
      case n: p.ScalaExpr => liftScalaExp(n)
      case n: p.Comment   => liftComment(n)
      case n: p.PCData    => liftPCData(n)
      case n: p.ProcInstr => liftProcInstr(n)
      case n: p.Unparsed  => liftUnparsed(n)
      case n: p.EntityRef => liftEntityRef(n)
    }

  implicit val liftNodeSeq: Liftable[Seq[p.Node]] = Liftable { nodes =>
    val additions = nodes.map { node => q"$$buf &+ $node" }
    q"""
      {
        val $$buf = new $sx.NodeBuffer
        ..$additions
        $$buf
      }
    """
  }

  val liftGroup: Liftable[p.Group] = Liftable { gr =>
    q"new $sx.Group(${gr.nodes})"
  }

  val liftElem: Liftable[p.Elem] = Liftable { e =>
    def liftAttributes(atts: Seq[p.Attribute]) = {
      val nil: Tree = q"$sx.Null"
      atts.foldRight(nil) {
        case (a, next) =>
          val prefix = a.pre.fold(q"null: String": Tree)(p => q"$p")
          val value = a.value match {
            case Seq(value) => q"$value"
            case values     => q"$values"
          }
          q"$sx.Attribute($prefix, ${a.key}, $value, $next)"
      }
    }

    def liftNameSpaces(nss: Seq[p.Attribute]): Tree = {
      val scope: Tree = q"$sx.quote.$$implicitScope"
      nss.foldLeft(scope) {
        case (parent, ns) =>
          val prefix = if (ns.pre.isDefined) q"${ns.key}" else q"null: String"
          val uri = ns.value.head match {
            case p.Text(text) => q"$text"
            case scalaExpr    => q"$scalaExpr"
          }
          q"$sx.NamespaceBinding($prefix, $uri, $parent)"
      }
    }

    def isNameSpace(a: p.Attribute) = a.pre.contains("xmlns")
    val (nss, atts) = e.attributes.partition(isNameSpace)

    def hasValidURI(ns: p.Attribute) = ns.value match {
      case Seq(_: p.Text | _: p.ScalaExpr) => true
      case _                               => false
    }
    require(nss.forall(hasValidURI))

    val prefix = e.prefix.fold(q"null: String": Tree)(p => q"$p")
    val attributes = liftAttributes(atts)
    val scope = liftNameSpaces(nss)
    val minimizeEmpty = q"${e.children.isEmpty}"

    if (nss.isEmpty) {
      q"$sx.Elem($prefix, ${e.label}, $attributes, $scope, $minimizeEmpty, ${e.children}: _*)"
    } else {
      q"""
        val $$tmpscope = $scope;
        {
          implicit val $$scope: $sx.NamespaceBinding = $$tmpscope
          $sx.Elem($prefix, ${e.label}, $attributes, $$scope, $minimizeEmpty, ${e.children}: _*)
        }
      """
    }

  }

  val liftText: Liftable[p.Text] = Liftable { t =>
    q"new $sx.Text(${t.text})"
  }

  val liftScalaExp: Liftable[p.ScalaExpr] = Liftable { se =>
    args(se.id)
  }

  val liftComment: Liftable[p.Comment] = Liftable { c =>
    q"new $sx.Comment(${c.commentText})"
  }

  val liftPCData: Liftable[p.PCData] = Liftable { pcd =>
    q"new $sx.PCData(${pcd.data})"
  }

  val liftProcInstr: Liftable[p.ProcInstr] = Liftable { pi =>
    q"new $sx.ProcInstr(${pi.target}, ${pi.proctext})"
  }

  val liftUnparsed: Liftable[p.Unparsed] = Liftable { u =>
    q"new $sx.Unparsed(${u.data})"
  }

  val liftEntityRef: Liftable[p.EntityRef] = Liftable { er =>
    q"new $sx.EntityRef(${er.entityName})"
  }
}
